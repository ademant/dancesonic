package org.dancesonic.player.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.HttpUrl
import org.dancesonic.player.AppContainer
import org.dancesonic.player.account.AccountClient
import org.dancesonic.player.account.AccountException
import org.dancesonic.player.account.ConnectionUri
import org.dancesonic.player.account.PairingUri
import org.dancesonic.player.dance.ChapterChange
import org.dancesonic.player.dance.DanceBrainzClient
import org.dancesonic.player.dance.DanceBrainzDescriptionClient
import org.dancesonic.player.dance.DanceBrainzException
import org.dancesonic.player.dance.DanceSuggestion
import org.dancesonic.player.data.ShoesView
import org.dancesonic.player.data.SyncMode
import org.dancesonic.player.data.SyncResult
import org.dancesonic.player.data.TrackDetail
import org.dancesonic.player.data.db.AlbumEntity
import org.dancesonic.player.data.db.ChapterEntity
import org.dancesonic.player.data.db.EditEntity
import org.dancesonic.player.data.db.PlaylistItemRow
import org.dancesonic.player.security.ServerSettingsStore
import org.dancesonic.player.subsonic.LanTls
import org.dancesonic.player.subsonic.ServerSettings
import org.dancesonic.player.subsonic.ServerUrl
import org.dancesonic.player.subsonic.SubsonicClient
import org.dancesonic.player.subsonic.SubsonicException
import org.dancesonic.player.subsonic.advertisesTinysonic
import org.dancesonic.player.subsonic.coverArtUrl

private const val STOP_TIMEOUT_MILLIS = 5_000L

private const val PENDING_CHAPTER_MESSAGE = "Sent. DanceBrainz holds the change until a moderator approves it."
private const val CHAPTER_NOT_SENT = "The chapter change was not sent"

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
 * Whether replacing [previous] with [next] points the app at another server. The synced catalog and the
 * sync point belong to one server, so they are cleared then. A new API key for the same server keeps them.
 */
internal fun connectionChangesServer(
    previous: ServerSettings?,
    next: ServerSettings,
): Boolean = previous?.baseUrl != next.baseUrl

/**
 * Connections saved before the header option existed send the key in the query. A folksonic or tinysonic
 * server is switched to the header the next time it syncs, so its key leaves the URLs and access logs.
 */
private suspend fun upgradeKeyHeader(
    store: ServerSettingsStore,
    settings: ServerSettings,
    client: SubsonicClient,
): ServerSettings {
    if (settings.keyInHeader || !client.advertisesTinysonic()) return settings
    return settings.withKeyInHeader(true).also { store.save(it) }
}

/**
 * Connection setup and catalog sync. Three ways in: a pairing QR code, a username and password
 * (exchanged for an API key), or an API key typed in. Every path ends with the same check and
 * only the API key is stored.
 */
class ConnectViewModel(
    private val container: AppContainer,
    private val account: AccountClient = AccountClient(),
) : ViewModel() {
    /** The stored connection, or null when not connected. The API key is included, so do not display it unmasked. */
    val settings: StateFlow<ServerSettings?> =
        container.settingsStore.settings
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val connected: StateFlow<Boolean> =
        settings
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
        withAddress(address) { url -> ServerSettings(url, key).withTlsPin(LanTls.connectPin(url)) }
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
            // The certificate is read before the password is sent, so a self-signed server is pinned first.
            val pin = LanTls.connectPin(url)
            val session = account.login(url, username.trim(), password)
            val key =
                try {
                    account.createApiKey(url, session)
                } finally {
                    // The session is only needed to mint the key. Revoke it either way, best effort.
                    endSessionQuietly(url, session)
                }
            ServerSettings(url, key).withTlsPin(pin)
        }
    }

    /**
     * Handles any code the scanner reads: a connection code from another device, or a pairing code from the
     * server's account page. Both are verified before they replace the stored connection.
     */
    fun connectFromScan(rawCode: String) {
        if (!ConnectionUri.isConnectionCode(rawCode)) {
            pairWithCode(rawCode)
            return
        }
        // A connection code from another device carries the server addresses and key itself.
        val shared =
            try {
                ConnectionUri.parse(rawCode)
            } catch (e: IllegalArgumentException) {
                _state.value = ConnectUiState(error = e.message)
                return
            }
        runConnect(
            danceBrainz = shared.danceBrainz,
        ) { shared.server.withTlsPin(LanTls.connectPin(shared.server.baseUrl)) }
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
            val pin = LanTls.connectPin(request.server)
            ServerSettings(request.server, account.redeemPairing(request)).withTlsPin(pin)
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
            container.audioCache.clear()
            container.waveforms.clear()
            container.keptCollections.forgetAll()
            container.settingsStore.clear()
            _state.value = ConnectUiState(error = warning)
        }
    }

    private fun withAddress(
        address: String,
        build: suspend (HttpUrl) -> ServerSettings,
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
     * app container is updated asynchronously from the store. Nothing is stored or cleared until the
     * check passes, so a failed attempt leaves the current connection untouched.
     */
    private fun runConnect(
        danceBrainz: HttpUrl? = null,
        obtain: suspend () -> ServerSettings,
    ) {
        _state.value = ConnectUiState(busy = true)
        viewModelScope.launch {
            try {
                val entered = obtain()
                val probe = SubsonicClient(entered)
                probe.checkConnection()
                // Only folksonic and tinysonic take the key in the header; the probe above used the query.
                val settings = if (probe.advertisesTinysonic()) entered.withKeyInHeader(true) else entered
                val verified =
                    if (settings.keyInHeader) {
                        SubsonicClient(
                            settings,
                        ).also { it.checkConnection() }
                    } else {
                        probe
                    }
                container.adoptCatalogClient(verified)
                danceBrainz?.let { DanceBrainzClient(it).checkServer() }
                if (connectionChangesServer(container.settingsStore.settings.first(), settings)) {
                    container.library.clearCatalog()
                    container.syncState.clear()
                    container.audioCache.clear()
                    container.waveforms.clear()
                    container.keptCollections.forgetAll()
                }
                container.settingsStore.save(settings)
                danceBrainz?.let { container.danceBrainzSettings.save(it) }
                _state.value = ConnectUiState()
                runSync(settings, incremental = false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: AccountException) {
                _state.value = ConnectUiState(error = e.message)
            } catch (e: SubsonicException) {
                _state.value = ConnectUiState(error = e.message ?: "Connection failed")
            } catch (e: DanceBrainzException) {
                _state.value = ConnectUiState(error = e.message)
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
                val first = container.catalogClient(settings)
                val active = upgradeKeyHeader(container.settingsStore, settings, first)
                val client = if (active.keyInHeader == settings.keyInHeader) first else container.catalogClient(active)
                val result =
                    if (incremental) {
                        container.library.syncIncremental(client)
                    } else {
                        container.library.sync(client) { done, total ->
                            _state.update { it.copy(syncProgress = SyncProgress(done, total)) }
                        }
                    }
                container.edits.flush()
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
@Suppress("TooManyFunctions") // The library screens and the DanceBrainz edits they call share this model.
class LibraryViewModel(private val container: AppContainer) : ViewModel() {
    val artists =
        container.library.artists()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), emptyList())

    val danceTypes =
        container.library.danceTypes()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), emptyList())

    /** The waveform of the track now playing, for the seek bar. Null while there is none. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val nowPlayingWaveform: StateFlow<List<Int>?> =
        container.player.state
            .map { it.nowPlayingId }
            .distinctUntilChanged()
            .flatMapLatest { id -> if (id == null) flowOf(null) else container.waveforms.observe(id) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), null)

    /** Shoes state by recording MBID, including changes not yet sent. */
    val shoes: StateFlow<Map<String, ShoesView>> =
        container.edits.observeShoes()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), emptyMap())

    /** Every DanceBrainz change not yet confirmed: queued, held for review, or in conflict. */
    val queue: StateFlow<List<EditEntity>> =
        container.edits.observeQueue()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), emptyList())

    /** True while a DanceBrainz token is stored on this phone, so ratings and dances can be changed. */
    val signedIn: StateFlow<Boolean> =
        container.danceBrainzTokens.credential
            .map { it != null }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), false)

    private val _suggestions = MutableStateFlow<List<DanceSuggestion>>(emptyList())

    /** Dance values that match the text being typed, best first. */
    val suggestions: StateFlow<List<DanceSuggestion>> = _suggestions.asStateFlow()

    private val _suggestError = MutableStateFlow<String?>(null)

    /** Why suggestions could not be looked up, such as having no connection. Null when they could. */
    val suggestError: StateFlow<String?> = _suggestError.asStateFlow()

    private var suggestJob: Job? = null

    init {
        // A change queued offline is sent as soon as the library is opened again.
        viewModelScope.launch { container.edits.flush() }
    }

    fun albums(artistId: String) = container.library.albums(artistId)

    fun tracks(albumId: String) = container.library.tracksWithTempo(albumId)

    fun tracksForDance(danceType: String) = container.library.tracksForDanceWithTempo(danceType)

    /** Scroll positions of the library lists, kept while the app runs. */
    val scrolls = ListScrollMemory()

    /** Which letter sections of the artist list and of the dance list are open. */
    val artistSections = LetterSections()
    val danceSections = LetterSections()

    /** An album's stored details, for the breadcrumb. */
    suspend fun album(id: String): AlbumEntity? = container.library.album(id)

    /** The dance names of [mbid] as the phone has them. */
    fun danceNames(mbid: String): Flow<List<String>> = container.edits.observeDances(mbid)

    /** The track now playing, with its details from the local database. Null when nothing plays. */
    val nowPlaying: StateFlow<TrackDetail?> =
        container.player.state
            .map { it.nowPlayingId }
            .distinctUntilChanged()
            .mapLatest { id -> id?.let { container.library.trackDetail(it) } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), null)

    /** The chapter marks of recording [mbid] as stored on the phone, in order. */
    fun chapters(mbid: String): Flow<List<ChapterEntity>> = container.library.chapters(mbid)

    private val _chapterMessage = MutableStateFlow<String?>(null)

    /** What the last chapter change said: that it waits for a moderator, or why it failed. Null when nothing to say. */
    val chapterMessage: StateFlow<String?> = _chapterMessage.asStateFlow()

    /** Marks a chapter at [startMs] of [mbid]. The list is reloaded from DanceBrainz afterwards. */
    fun markChapter(
        mbid: String,
        startMs: Long,
    ) = chapterWrite { container.chapters.add(mbid, startMs) }

    /** Applies [change] to the chapter that starts at [startMs]. */
    fun changeChapter(
        mbid: String,
        startMs: Long,
        change: ChapterChange,
    ) = chapterWrite { container.chapters.update(mbid, startMs, change) }

    /** Removes the chapter that starts at [startMs]. */
    fun deleteChapter(
        mbid: String,
        startMs: Long,
    ) = chapterWrite { container.chapters.delete(mbid, startMs) }

    /** Clears the message of the last chapter change, once it has been shown. */
    fun clearChapterMessage() {
        _chapterMessage.value = null
    }

    private fun chapterWrite(write: suspend () -> Boolean) {
        viewModelScope.launch {
            _chapterMessage.value =
                try {
                    if (write()) PENDING_CHAPTER_MESSAGE else null
                } catch (e: DanceBrainzException) {
                    e.message ?: CHAPTER_NOT_SENT
                }
        }
    }

    /**
     * The cover art of album [albumId] at most [size] pixels on its longer side, on the configured server. Null while
     * no server is set. The URL holds no API key; the image loader adds it.
     */
    fun coverUrl(
        albumId: String,
        size: Int,
    ): String? = container.currentSettings?.coverArtUrl(albumId, size)?.toString()

    /** The DanceBrainz page of recording [mbid]. The page shows its dance description. */
    fun danceBrainzPageUrl(mbid: String): String? =
        container.currentDanceBrainzUrl.resolve("recording/$mbid")?.toString()

    private val descriptionAnswers = mutableMapOf<String, Boolean>()

    /**
     * True when DanceBrainz stores a dance description for [mbid]. Each answer is kept for the session. When the check
     * fails (offline, server unreachable), it answers false and is asked again next time.
     */
    @Suppress("SwallowedException") // A failed check is shown as no link; the next visit asks again.
    suspend fun hasDanceDescription(mbid: String): Boolean {
        descriptionAnswers[mbid]?.let { return it }
        return try {
            DanceBrainzDescriptionClient(container.currentDanceBrainzUrl).hasDescription(mbid).also {
                descriptionAnswers[mbid] = it
            }
        } catch (e: DanceBrainzException) {
            false
        }
    }

    /** A track's details from the local database, for the preview before it plays. */
    suspend fun trackDetail(id: String): TrackDetail? = container.library.trackDetail(id)

    /** Which albums and playlists are kept on the phone; see [KeepOfflineToggle]. */
    val keptCollections = container.keptCollections

    /**
     * Sets the shoes rating of [mbid] to [value], or clears it when [value] is null. It is shown at once and sent
     * now or later.
     */
    fun rate(
        mbid: String,
        value: Int?,
    ) {
        viewModelScope.launch {
            if (value == null) container.edits.clearRating(mbid) else container.edits.rate(mbid, value)
        }
    }

    /** Looks up dance values for [query] after a short pause, so each keystroke does not send a request. */
    fun suggestDances(query: String) {
        suggestJob?.cancel()
        suggestJob =
            viewModelScope.launch {
                _suggestError.value = null
                if (query.isBlank()) {
                    _suggestions.value = emptyList()
                    return@launch
                }
                delay(SUGGEST_PAUSE_MILLIS)
                try {
                    _suggestions.value = container.edits.suggestDances(query)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: DanceBrainzException) {
                    _suggestions.value = emptyList()
                    _suggestError.value = e.message
                }
            }
    }

    /** Queues adding [suggestion] to [mbid] with [qualifier] (learn, disco or both). */
    fun addDance(
        mbid: String,
        suggestion: DanceSuggestion,
        qualifier: String,
    ) {
        viewModelScope.launch { container.edits.addDance(mbid, suggestion, qualifier) }
    }

    /** Queues removing the dance named [name] from [mbid]. */
    fun removeDance(
        mbid: String,
        name: String,
    ) {
        viewModelScope.launch { container.edits.removeDance(mbid, name) }
    }

    /** Removes a queued, held or conflicting change from the queue. */
    fun dismissChange(id: Long) {
        viewModelScope.launch { container.edits.dismiss(id) }
    }

    /** Sends the queued changes again, for the Send again action. */
    fun retryChanges() {
        viewModelScope.launch { container.edits.flush() }
    }

    /** Reads the public shoes median and, when signed in, the viewer's rating for the tracks on screen. */
    fun refreshShoes(mbids: List<String>) {
        viewModelScope.launch { container.edits.refreshShoes(mbids) }
    }

    private companion object {
        const val SUGGEST_PAUSE_MILLIS = 300L
    }
}

/** Course playlists, stored on the device. */
class PlaylistViewModel(private val container: AppContainer) : ViewModel() {
    val playlists =
        container.playlists.playlists()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), emptyList())

    fun playlist(id: Long) = container.playlists.playlist(id)

    /** Which albums and playlists are kept on the phone; see [KeepOfflineToggle]. */
    val keptCollections = container.keptCollections

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
            val byId = container.library.tracksWithTempoFor(ids).associateBy { it.track.id }
            container.player.play(ids.mapNotNull { byId[it] })
        }
    }
}
