package org.dancesonic.player.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.HttpUrl
import org.dancesonic.player.AppContainer
import org.dancesonic.player.account.AccountClient
import org.dancesonic.player.account.AccountException
import org.dancesonic.player.account.PairingUri
import org.dancesonic.player.data.SyncMode
import org.dancesonic.player.data.SyncResult
import org.dancesonic.player.data.db.PlaylistItemRow
import org.dancesonic.player.subsonic.ServerSettings
import org.dancesonic.player.subsonic.ServerUrl
import org.dancesonic.player.subsonic.SubsonicClient
import org.dancesonic.player.subsonic.SubsonicException

private const val STOP_TIMEOUT_MILLIS = 5_000L

/** How long a successful check stays fresh before the next automatic check on start. */
private const val CHECK_INTERVAL_MILLIS = 24L * 60 * 60 * 1000

/** How long a full listing stays fresh before the automatic check runs one. */
private const val FULL_SYNC_INTERVAL_MILLIS = 7L * 24 * 60 * 60 * 1000

data class ConnectUiState(
    val busy: Boolean = false,
    val error: String? = null,
    val lastSync: String? = null,
    /** Albums fetched so far in the running sync, or null when no sync is running. */
    val syncProgress: SyncProgress? = null,
)

data class SyncProgress(val done: Int, val total: Int)

/**
 * Why a refresh cannot start, or null if it can. A refresh without saved settings used to return
 * without any message, which looked like a broken button.
 */
internal fun refreshBlockedReason(settings: ServerSettings?): String? =
    if (settings == null) "Not connected to a server. Connect again to sync." else null

/**
 * Connection setup and catalog sync. Three ways in: a pairing QR code, a username and password
 * (exchanged for an API key), or an API key typed in. Every path ends with the same check and
 * only the API key is stored.
 */
class ConnectViewModel(
    private val container: AppContainer,
    private val account: AccountClient = AccountClient(),
) : ViewModel() {
    val connected: StateFlow<Boolean> =
        container.settingsStore.settings
            .map { it != null }
            .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private val _state = MutableStateFlow(ConnectUiState())
    val state: StateFlow<ConnectUiState> = _state.asStateFlow()

    /** Shows an error that happened outside the ViewModel, such as a failed scanner launch. */
    fun reportError(message: String) {
        _state.value = ConnectUiState(error = message)
    }

    /** Connects with a server address and an API key typed by the user. */
    fun connectWithApiKey(
        address: String,
        apiKey: String,
    ) {
        val key = apiKey.trim()
        if (key.isBlank()) {
            _state.value = ConnectUiState(error = "Enter an API key.")
            return
        }
        withAddress(address) { url -> ServerSettings(url, key) }
    }

    /** Signs in with a username and password, then keeps only the API key the server issues. */
    fun signInWithPassword(
        address: String,
        username: String,
        password: String,
    ) {
        if (username.isBlank() || password.isEmpty()) {
            _state.value = ConnectUiState(error = "Enter your username and password.")
            return
        }
        val url = parseAddress(address) ?: return
        runConnect {
            val session = account.login(url, username.trim(), password)
            val key =
                try {
                    account.createApiKey(url, session)
                } finally {
                    // The session is only needed to mint the key. Revoke it either way, best effort.
                    endSessionQuietly(url, session)
                }
            ServerSettings(url, key)
        }
    }

    /** Redeems a scanned pairing code for an API key. The server address comes from the code. */
    fun pairWithCode(rawCode: String) {
        val request =
            try {
                PairingUri.parse(rawCode)
            } catch (e: IllegalArgumentException) {
                _state.value = ConnectUiState(error = e.message)
                return
            }
        runConnect {
            ServerSettings(request.server, account.redeemPairing(request))
        }
    }

    /**
     * Brings the local catalog up to date. By default only what changed on the server is fetched.
     * [full] re-lists the whole catalog: it repairs what the change feed cannot see, and is slow on large
     * libraries. Safe to call while playing.
     */
    fun sync(full: Boolean = false) {
        val settings = container.currentSettings
        if (settings == null) {
            _state.value = _state.value.copy(error = refreshBlockedReason(null))
            return
        }
        runSync(settings, incremental = !full)
    }

    /**
     * Revokes this app's key on the server (best effort), clears the synced catalog and forgets the
     * server. Playlists stay on the device. If the server cannot be reached, the local data is still
     * removed and the user is told to revoke the key on the account page.
     */
    fun disconnect() {
        val settings = container.currentSettings
        viewModelScope.launch {
            var warning: String? = null
            if (settings != null) {
                try {
                    account.revokeOwnKey(settings.baseUrl, settings.apiKey)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: AccountException) {
                    warning = "Could not revoke the key on the server (${e.message}). Revoke it on the account page."
                }
            }
            container.library.clearCatalog()
            container.syncState.clear()
            container.settingsStore.clear()
            _state.value = ConnectUiState(error = warning)
        }
    }

    private fun withAddress(
        address: String,
        build: (HttpUrl) -> ServerSettings,
    ) {
        val url = parseAddress(address) ?: return
        runConnect { build(url) }
    }

    private fun parseAddress(address: String): HttpUrl? =
        try {
            ServerUrl.parse(address)
        } catch (e: IllegalArgumentException) {
            _state.value = ConnectUiState(error = e.message)
            null
        }

    /**
     * Runs [obtain] to get settings, verifies them against the server, stores them and starts the
     * first sync. The sync uses the verified settings directly, because the in-memory copy in the
     * app container is updated asynchronously from the store.
     */
    private fun runConnect(obtain: suspend () -> ServerSettings) {
        _state.value = ConnectUiState(busy = true)
        viewModelScope.launch {
            try {
                val settings = obtain()
                SubsonicClient(settings).checkConnection()
                container.settingsStore.save(settings)
                _state.value = ConnectUiState()
                startSync(settings)
            } catch (e: CancellationException) {
                throw e
            } catch (e: AccountException) {
                _state.value = ConnectUiState(error = e.message)
            } catch (e: SubsonicException) {
                _state.value = ConnectUiState(error = e.message ?: "Connection failed")
            } catch (e: IllegalArgumentException) {
                _state.value = ConnectUiState(error = e.message ?: "Invalid settings")
            }
        }
    }

    /**
     * Asks the server whether anything changed, at most once a day. Runs without a Refresh tap, so it
     * is quiet when nothing changed. Skipped while another sync runs.
     */
    fun checkForChanges() {
        val settings = container.currentSettings ?: return
        if (_state.value.busy) return
        viewModelScope.launch {
            val url = settings.baseUrl.toString()
            if (container.library.incrementalCheckDue(url, CHECK_INTERVAL_MILLIS)) {
                // Once a week the check is a full listing, which also picks up tag-only edits.
                val weeklyFull = container.library.fullSyncDue(url, FULL_SYNC_INTERVAL_MILLIS)
                runSync(settings, incremental = !weeklyFull)
            }
        }
    }

    private fun startSync(settings: ServerSettings) = runSync(settings, incremental = false)

    /**
     * An incremental sync asks the server for changes and fetches only those; without a saved sync point
     * it does a full listing. A full sync lists everything, and shows progress per album.
     */
    private fun runSync(
        settings: ServerSettings,
        incremental: Boolean,
    ) {
        _state.value = _state.value.copy(busy = true, error = null, syncProgress = null)
        viewModelScope.launch {
            try {
                val client = container.catalogClient(settings)
                val result =
                    if (incremental) {
                        container.library.syncIncremental(client)
                    } else {
                        container.library.sync(client) { done, total ->
                            _state.update { it.copy(syncProgress = SyncProgress(done, total)) }
                        }
                    }
                _state.value = ConnectUiState(lastSync = syncMessage(result))
            } catch (e: CancellationException) {
                throw e
            } catch (e: SubsonicException) {
                _state.value = ConnectUiState(error = "Sync failed: ${e.message}")
            }
        }
    }

    private fun syncMessage(result: SyncResult): String {
        val danceNote =
            if (result.danceLookupsFailed > 0) ", ${result.danceLookupsFailed} dance lookups failed" else ""
        return when (result.mode) {
            SyncMode.Unchanged -> "No changes on the server since the last sync."
            SyncMode.Changes -> "Applied ${result.rowsWritten} changes$danceNote"
            SyncMode.Full ->
                "Synced ${result.artists} artists, ${result.albums} albums, ${result.tracks} tracks$danceNote"
        }
    }

    @Suppress("SwallowedException")
    private suspend fun endSessionQuietly(
        url: HttpUrl,
        session: String,
    ) {
        try {
            account.logout(url, session)
        } catch (e: CancellationException) {
            throw e
        } catch (e: AccountException) {
            // Deliberately ignored: the key is already issued and the session expires on its own.
        }
    }
}

/** Passes library flows to the UI. Room is the source of truth; the UI never waits on the network. */
class LibraryViewModel(private val container: AppContainer) : ViewModel() {
    val artists =
        container.library.artists()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), emptyList())

    val danceTypes =
        container.library.danceTypes()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), emptyList())

    fun albums(artistId: String) = container.library.albums(artistId)

    fun tracks(albumId: String) = container.library.tracks(albumId)

    fun tracksForDance(danceType: String) = container.library.tracksForDance(danceType)
}

/** Course playlists, stored on the device. */
class PlaylistViewModel(private val container: AppContainer) : ViewModel() {
    val playlists =
        container.playlists.playlists()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), emptyList())

    fun playlist(id: Long) = container.playlists.playlist(id)

    fun groups(playlistId: Long) = container.playlists.groups(playlistId)

    fun create(
        name: String,
        onCreated: (Long) -> Unit,
    ) {
        viewModelScope.launch { onCreated(container.playlists.create(name)) }
    }

    fun rename(
        playlistId: Long,
        name: String,
    ) {
        viewModelScope.launch { container.playlists.rename(playlistId, name) }
    }

    fun delete(playlistId: Long) {
        viewModelScope.launch { container.playlists.deleteById(playlistId) }
    }

    fun addTrack(
        playlistId: Long,
        trackId: String,
        danceType: String?,
    ) {
        viewModelScope.launch { container.playlists.addTrack(playlistId, trackId, danceType) }
    }

    fun moveToGroup(
        item: PlaylistItemRow,
        danceType: String?,
    ) {
        viewModelScope.launch { container.playlists.moveToGroup(item, danceType) }
    }

    fun removeItem(item: PlaylistItemRow) {
        viewModelScope.launch { container.playlists.removeItem(item) }
    }

    /** Plays the whole playlist in position order. */
    fun playAll(playlistId: Long) {
        viewModelScope.launch {
            val ids = container.playlists.orderedTrackIds(playlistId)
            val byId = container.library.tracks(ids).associateBy { it.id }
            container.player.play(ids.mapNotNull { byId[it] })
        }
    }
}
