package org.dancesonic.player.ui

import android.os.Build
import android.util.Log
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
import org.dancesonic.player.account.PairingDecision
import org.dancesonic.player.account.PairingRequest
import org.dancesonic.player.account.PairingUri
import org.dancesonic.player.account.PairingVerifier
import org.dancesonic.player.account.VerificationCode
import org.dancesonic.player.dance.ChapterChange
import org.dancesonic.player.dance.DanceBrainzClient
import org.dancesonic.player.dance.DanceBrainzDescriptionClient
import org.dancesonic.player.dance.DanceBrainzException
import org.dancesonic.player.dance.DanceSuggestion
import org.dancesonic.player.dance.MeterValue
import org.dancesonic.player.data.MeterView
import org.dancesonic.player.data.ShoesView
import org.dancesonic.player.data.TrackDetail
import org.dancesonic.player.data.db.AlbumEntity
import org.dancesonic.player.data.db.ChapterEntity
import org.dancesonic.player.data.db.EditEntity
import org.dancesonic.player.data.db.PlaylistItemRow
import org.dancesonic.player.security.ServerSettingsStore
import org.dancesonic.player.subsonic.CleartextPolicy
import org.dancesonic.player.subsonic.DeviceKeys
import org.dancesonic.player.subsonic.KeystoreDeviceSigner
import org.dancesonic.player.subsonic.LanPins
import org.dancesonic.player.subsonic.LocalNetworkAccess
import org.dancesonic.player.subsonic.LocalNetworkBlockedException
import org.dancesonic.player.subsonic.ServerAuth
import org.dancesonic.player.subsonic.ServerSettings
import org.dancesonic.player.subsonic.ServerUrl
import org.dancesonic.player.subsonic.SubsonicClient
import org.dancesonic.player.subsonic.SubsonicException
import org.dancesonic.player.subsonic.advertisesTinysonic
import org.dancesonic.player.subsonic.coverArtUrl
import org.dancesonic.player.subsonic.localNetworkBlock
import java.io.IOException

private const val STOP_TIMEOUT_MILLIS = 5_000L

private const val TAG = "DanceSonicSync"

private const val PENDING_CHAPTER_MESSAGE = "Sent. DanceBrainz holds the change until a moderator approves it."
private const val CHAPTER_NOT_SENT = "The chapter change was not sent"

/** How long a check stays fresh before the next automatic check on start: a week (#121). */
internal const val CHECK_INTERVAL_MILLIS = 7L * 24 * 60 * 60 * 1000

data class ConnectUiState(
    val busy: Boolean = false,
    val error: String? = null,
    /** What the app is waiting for, such as the owner's approval of a pairing. */
    val status: String? = null,
    /** The code to compare with the account page while a pairing waits for both sides to confirm it. */
    val verificationCode: String? = null,
    /** The certificate fingerprint of a self-signed server, while the user is asked to trust it (#126). */
    val certificateFingerprint: String? = null,
    /** A scanned code's other server, while the user is asked whether it replaces the current one (#135). */
    val replacement: ServerReplacement? = null,
    /** True when the last connect failed because the app may not use the local network (#124). */
    val localNetworkBlocked: Boolean = false,
    val lastSync: String? = null,
    /** Albums fetched so far in the running sync, or null when no sync is running. */
    val syncProgress: SyncProgress? = null,
)

data class SyncProgress(
    val done: Int,
    val total: Int,
)

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
    if (settings.auth !is ServerAuth.ApiKey || settings.keyInHeader || !client.advertisesTinysonic()) return settings
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

    private val _allowCleartext = MutableStateFlow(false)

    /**
     * Whether the next connection may use plain http to a private network server (#84). Off until the user turns it on
     * and acknowledges the warning; it starts as the stored connection has it.
     */
    val allowCleartext: StateFlow<Boolean> = _allowCleartext.asStateFlow()

    init {
        viewModelScope.launch {
            container.settingsStore.settings
                .first()
                ?.let { _allowCleartext.value = it.allowCleartext }
        }
    }

    /** Sets the http opt-in for the next connection. The UI turns it on only after the warning is acknowledged. */
    fun setAllowCleartext(allowed: Boolean) {
        _allowCleartext.value = allowed
    }

    /**
     * Shows the pairing's verification code on this screen. The user's answer goes to [PairingVerifier.answer]:
     * [PairingDecision.MATCH] when the code equals the one on the account page.
     */
    val verifier: PairingVerifier =
        PairingVerifier(show = { code ->
            _state.value =
                _state.value.copy(
                    verificationCode = code,
                    status = if (code == null) _state.value.status else VERIFY_STATUS,
                )
        })

    /**
     * Shows the fingerprint of a self-signed server certificate before anything secret is sent to the server (#126).
     * The user's answer goes to [CertificateCheck.answer].
     */
    val certificateCheck: CertificateCheck =
        CertificateCheck(show = { fingerprint -> _state.update { it.copy(certificateFingerprint = fingerprint) } })

    /**
     * Asks before a scanned code replaces the stored connection with another server's (#135). The user's answer goes
     * to [ReplaceCheck.answer].
     */
    val replaceCheck: ReplaceCheck =
        ReplaceCheck(show = { change -> _state.update { it.copy(replacement = change) } })

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
        val allowCleartext = _allowCleartext.value
        withAddress(address) { url ->
            ServerSettings(
                url,
                key,
                allowCleartext = allowCleartext,
            ).withTlsPin(trustedPin(url, null, certificateCheck::await))
        }
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
        val allowCleartext = _allowCleartext.value
        withAddress(address) { url ->
            // The certificate is checked before the password is sent, so a self-signed server is pinned first.
            val pin = trustedPin(url, null, certificateCheck::await)
            val session = account.login(url, username.trim(), password)
            val key =
                try {
                    account.createApiKey(url, session)
                } finally {
                    // The session is only needed to mint the key. Revoke it either way, best effort.
                    account.logoutQuietly(url, session)
                }
            ServerSettings(url, key, allowCleartext = allowCleartext).withTlsPin(pin)
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
                ConnectionUri.parse(rawCode, _allowCleartext.value)
            } catch (e: IllegalArgumentException) {
                _state.value = ConnectUiState(error = e.message)
                return
            }
        runConnect(
            shared.server.baseUrl,
            danceBrainz = shared.danceBrainz,
        ) {
            // Nothing is checked or sent before the user agrees to leave the current server.
            replaceCheck.confirm(container.settingsStore.settings.first(), shared.server.baseUrl)
            shared.server.withTlsPin(trustedPin(shared.server.baseUrl, null, certificateCheck::await))
        }
    }

    /**
     * Pairs this install as a device (folksonic #101). The server address and the pin come from the code. The app keeps
     * no API key: its requests are signed with a key in the Keystore once the owner approves the pairing.
     */
    fun pairWithCode(rawCode: String) {
        val request =
            try {
                PairingUri.parse(rawCode, _allowCleartext.value)
            } catch (e: IllegalArgumentException) {
                _state.value = ConnectUiState(error = e.message)
                return
            }
        runConnect(request.server) {
            replaceCheck.confirm(container.settingsStore.settings.first(), request.server)
            // The code was just scanned, so it expires about one pairing window from now.
            val expiresAt = System.currentTimeMillis() + AccountClient.PAIRING_WAIT_MILLIS
            // A pin in the code must match before the token is sent; a mismatch never becomes active (#126).
            val pin = trustedPin(request.server, request.pin, certificateCheck::await)
            pairThisDevice(
                account,
                request,
                pin,
                verify = verifier::await,
                awaitApproval = { device ->
                    countingDown(expiresAt, show = { status -> _state.update { it.copy(status = status) } }) {
                        val left = (expiresAt - System.currentTimeMillis()).coerceAtLeast(0)
                        device.awaitDeviceApproval(request.server, timeoutMillis = left)
                    }
                },
            )
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
            val key = settings?.apiKey
            if (settings != null && key != null) {
                try {
                    account.revokeOwnKey(settings.baseUrl, key)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: AccountException) {
                    warning = "Could not revoke the key on the server (${e.message}). Revoke it on the account page."
                }
            }
            val device = settings?.auth as? ServerAuth.Device
            if (device != null) {
                DeviceKeys.delete(device.alias)
                warning = "This device stays paired on the server. Remove it on the account page."
            }
            container.forgetServerData()
            container.settingsStore.clear()
            // The store's flow forgets them too, but later; nothing may use the old server's pin or http opt-in now.
            LanPins.remember(null)
            CleartextPolicy.remember(null)
            _state.value = ConnectUiState(error = warning)
        }
    }

    private fun withAddress(
        address: String,
        build: suspend (HttpUrl) -> ServerSettings,
    ) {
        val url =
            try {
                ServerUrl.parse(address, _allowCleartext.value)
            } catch (e: IllegalArgumentException) {
                _state.value = ConnectUiState(error = e.message)
                return
            }
        runConnect(url) { build(url) }
    }

    /**
     * Runs [obtain] to get settings, verifies them against the server, stores them and starts the
     * first sync. The sync uses the verified settings directly, because the in-memory copy in the
     * app container is updated asynchronously from the store. Nothing is stored or cleared until the
     * check passes, so a failed attempt leaves the current connection untouched.
     */
    private fun runConnect(
        server: HttpUrl,
        danceBrainz: HttpUrl? = null,
        obtain: suspend () -> ServerSettings,
    ) {
        if (LocalNetworkAccess.needsPermission(server) && !LocalNetworkAccess.allowed()) {
            // Nothing is sent: Android would block the connection, and it would only time out (#124).
            _state.value = ConnectUiState(error = LocalNetworkBlockedException.MESSAGE, localNetworkBlocked = true)
            return
        }
        // The address passed ServerUrl.parse with this opt-in; the policy checks it again (#140).
        val optIn = _allowCleartext.value
        _state.value = ConnectUiState(busy = true)
        viewModelScope.launch {
            try {
                // An http server the user allowed may be reached while it is set up, before it is stored.
                val connected =
                    CleartextPolicy.whileConnecting(server, optIn) { verifyAndStore(obtain, danceBrainz) }
                _state.value = ConnectUiState()
                runSync(connected.settings, incremental = false, notice = connected.notice)
            } catch (e: CancellationException) {
                throw e
            } catch (e: AccountException) {
                _state.value = ConnectUiState(error = e.message, localNetworkBlocked = e.localNetworkBlock() != null)
            } catch (e: SubsonicException) {
                _state.value =
                    ConnectUiState(
                        error = e.message ?: "Connection failed",
                        localNetworkBlocked = e.localNetworkBlock() != null,
                    )
            } catch (e: DanceBrainzException) {
                _state.value = ConnectUiState(error = e.message)
            } catch (e: IllegalArgumentException) {
                _state.value = ConnectUiState(error = e.message ?: "Invalid settings")
            } catch (e: IOException) {
                // After SubsonicException, which is an IOException too: a failure no client mapped, such as a
                // blocked local network during a pairing request.
                val blocked = e.localNetworkBlock()
                _state.value =
                    ConnectUiState(
                        error = blocked?.message ?: "Could not reach the server.",
                        localNetworkBlocked = blocked != null,
                    )
            } finally {
                // A pin trusted for this setup is forgotten; a stored connection keeps its own (#126).
                LanPins.endSetup()
            }
        }
    }

    /** What [verifyAndStore] stored, and anything the user should know about it. */
    private class Connected(
        val settings: ServerSettings,
        val notice: String?,
    )

    /** The checks and the store of [runConnect]. */
    private suspend fun verifyAndStore(
        obtain: suspend () -> ServerSettings,
        danceBrainz: HttpUrl?,
    ): Connected {
        val entered = obtain()
        val probe = SubsonicClient(entered)
        probe.checkConnection()
        val settings = settingsAfterProbe(entered, probe.advertisesTinysonic())
        val verified =
            if (settings.keyInHeader) {
                SubsonicClient(
                    settings,
                ).also { it.checkConnection() }
            } else {
                probe
            }
        danceBrainz?.let { DanceBrainzClient(it).checkServer() }
        val previous = container.settingsStore.settings.first()
        if (connectionChangesServer(previous, settings)) container.forgetServerData()
        container.adoptCatalogClient(verified)
        container.settingsStore.save(settings)
        // The store's flow updates the policies too, but later: the first sync starts now.
        CleartextPolicy.remember(settings)
        LanPins.remember(settings)
        danceBrainz?.let { container.danceBrainzSettings.save(it) }
        return Connected(settings, revokeReplacedKey(previous, settings))
    }

    /**
     * After a device pairing replaces an API key for the same server, revokes that key with itself (#68), so it does
     * not stay valid until someone revokes it by hand. The new settings no longer hold it. Best effort: returns a
     * notice when the server could not be asked.
     */
    private suspend fun revokeReplacedKey(
        previous: ServerSettings?,
        next: ServerSettings,
    ): String? {
        val oldKey = replacedKeyToRevoke(previous, next) ?: return null
        val server = next.baseUrl
        return try {
            account.revokeOwnKey(server, oldKey)
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: AccountException) {
            "Paired. The old API key could not be revoked (${e.message}). Revoke it on the account page."
        }
    }

    /**
     * Asks a server with the change feed what changed, at most once a week (#121). Runs without a Refresh tap, so it
     * is quiet when nothing changed; a standard Subsonic server is not checked at all. Skipped while another sync runs.
     */
    fun checkForChanges() {
        val settings = container.currentSettings ?: return
        if (_state.value.busy) return
        // Busy, so a Refresh tap does not start a second sync next to this one.
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            try {
                val result = container.library.scheduledSync(container.catalogClient(settings), CHECK_INTERVAL_MILLIS)
                if (result != null) container.edits.flush()
                _state.update { it.copy(busy = false, lastSync = result?.summary() ?: it.lastSync) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: SubsonicException) {
                // Quiet: the stored catalog still works, and the next check or a Refresh tries again.
                Log.d(TAG, "Scheduled check skipped: ${e.javaClass.simpleName}")
                _state.update { it.copy(busy = false) }
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
        notice: String? = null,
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
                _state.value = ConnectUiState(lastSync = result.summary(), status = notice)
            } catch (e: CancellationException) {
                throw e
            } catch (e: SubsonicException) {
                _state.value = ConnectUiState(error = "Sync failed: ${e.message}", status = notice)
            }
        }
    }
}

/** Passes library flows to the UI. Room is the source of truth; the UI never waits on the network. */
@Suppress("TooManyFunctions") // The library screens and the DanceBrainz edits they call share this model.
class LibraryViewModel(
    private val container: AppContainer,
) : ViewModel() {
    val artists =
        container.library
            .artists()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), emptyList())

    val danceTypes =
        container.library
            .danceTypes()
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
        container.edits
            .observeShoes()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), emptyMap())

    /** The meter and sub-meter by recording MBID, including a meter change not yet sent. */
    val meters: StateFlow<Map<String, MeterView>> =
        container.edits.meters
            .observe()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), emptyMap())

    /** Every DanceBrainz change not yet confirmed: queued, held for review, or in conflict. */
    val queue: StateFlow<List<EditEntity>> =
        container.edits
            .observeQueue()
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

    /**
     * A track's details from the local database, for the preview before it plays. The track is compared with the
     * server in the background (#121), throttled, so the next look shows what changed.
     */
    suspend fun trackDetail(id: String): TrackDetail? {
        container.refreshTrack(id)
        return container.library.trackDetail(id)
    }

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

    /** The meters and sub-meters DanceBrainz offers. Throws [DanceBrainzException] when it cannot be reached. */
    suspend fun meterOptions(): List<MeterValue> = container.edits.meters.options()

    /** Queues setting the meter of [mbid] to [name], a sub-meter when [parentName] names its meter. */
    fun setMeter(
        mbid: String,
        name: String,
        parentName: String?,
    ) {
        viewModelScope.launch { container.edits.meters.set(mbid, name, parentName) }
    }

    /** The dance description of [mbid], or null when none is stored. Throws [DanceBrainzException] on failure. */
    suspend fun danceDescription(mbid: String): String? =
        DanceBrainzDescriptionClient(container.currentDanceBrainzUrl).descriptionText(mbid)

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
class PlaylistViewModel(
    private val container: AppContainer,
) : ViewModel() {
    val playlists =
        container.playlists
            .playlists()
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

private const val MILLIS_PER_SECOND = 1_000L
private const val SECONDS_PER_MINUTE = 60L

/** The status while a pairing waits for the owner, with the time left until the code expires, such as `14:05`. */
internal fun waitingStatus(remainingMillis: Long): String {
    if (remainingMillis <= 0) return "The code expired. Create a new code on the account page."
    val seconds = (remainingMillis + MILLIS_PER_SECOND - 1) / MILLIS_PER_SECOND
    val clock = "${seconds / SECONDS_PER_MINUTE}:${(seconds % SECONDS_PER_MINUTE).toString().padStart(2, '0')}"
    return "Waiting for approval on the account page. The code expires in $clock."
}

/**
 * The API key a new connection replaces, when it should be revoked: the old connection had a key and the new one is a
 * device paired with the same server. Null otherwise; a key for another server is revoked by Disconnect only.
 */
internal fun replacedKeyToRevoke(
    previous: ServerSettings?,
    next: ServerSettings,
): String? {
    val oldKey = previous?.apiKey ?: return null
    return oldKey.takeIf { next.auth is ServerAuth.Device && previous.baseUrl == next.baseUrl }
}

private const val VERIFY_STATUS =
    "Compare this code with the one on the account page. Confirm only if they are the same."
private const val CANCELLED_ON_PHONE = "The pairing was cancelled on this phone."
private const val MAX_DEVICE_NAME = 100

/**
 * Creates the device key, sends its public half with the token, and completes the pairing. When the server asks for it,
 * both sides first show the same verification code, and [verify] returns the phone's answer to it (folksonic #103).
 * The new key is deleted again unless the pairing completes, so a failed attempt leaves nothing behind in the Keystore.
 */
private suspend fun pairThisDevice(
    account: AccountClient,
    request: PairingRequest,
    pin: String?,
    verify: suspend (String) -> PairingDecision,
    awaitApproval: suspend (AccountClient) -> Unit,
): ServerSettings {
    val alias = DeviceKeys.create()
    // An http server got past PairingUri.parse only with the user's opt-in, so its settings keep that opt-in.
    val settings =
        ServerSettings(request.server, ServerAuth.Device(alias), tlsPin = pin, allowCleartext = !request.server.isHttps)
    var paired = false
    try {
        val jwk = KeystoreDeviceSigner(alias).publicJwk()
        val pending = account.startDevicePairing(request, deviceName(), jwk)
        if (pending.confirm) {
            val decision = verify(VerificationCode.of(request.token, jwk))
            AccountClient.forDevice(settings).confirmDevicePairing(request.server, decision)
            if (decision == PairingDecision.CANCEL) throw AccountException(CANCELLED_ON_PHONE)
        }
        awaitApproval(AccountClient.forDevice(settings))
        paired = true
    } finally {
        if (!paired) DeviceKeys.delete(alias)
    }
    return settings
}

/**
 * The settings to save after the probe. An API key moves to the header on folksonic and tinysonic, which needs a second
 * check. A device has no key, so it is kept as it is and is not checked twice.
 */
internal fun settingsAfterProbe(
    entered: ServerSettings,
    advertisesTinysonic: Boolean,
): ServerSettings =
    if (entered.auth is ServerAuth.ApiKey && advertisesTinysonic) entered.withKeyInHeader(true) else entered

private fun deviceName(): String = "DanceSonic on ${Build.MODEL}".take(MAX_DEVICE_NAME)
