package org.dancesonic.player

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import androidx.media3.datasource.okhttp.OkHttpDataSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import org.dancesonic.player.dance.DanceBrainzClient
import org.dancesonic.player.dance.DanceBrainzSettingsStore
import org.dancesonic.player.dance.DanceBrainzSource
import org.dancesonic.player.dance.DanceBrainzTokenStore
import org.dancesonic.player.data.LibraryRepository
import org.dancesonic.player.data.LogcatSyncTimings
import org.dancesonic.player.data.PlaylistRepository
import org.dancesonic.player.data.SyncStateStore
import org.dancesonic.player.data.SyncTimings
import org.dancesonic.player.data.db.DanceSonicDatabase
import org.dancesonic.player.data.db.TrackEntity
import org.dancesonic.player.playback.AudioCache
import org.dancesonic.player.playback.AudioPrefetcher
import org.dancesonic.player.playback.PlaybackSettings
import org.dancesonic.player.playback.PlaybackSettingsStore
import org.dancesonic.player.playback.PlayerController
import org.dancesonic.player.playback.StreamDataSource
import org.dancesonic.player.playback.StreamLengths
import org.dancesonic.player.security.ServerSettingsStore
import org.dancesonic.player.subsonic.ServerSettings
import org.dancesonic.player.subsonic.SubsonicClient
import org.dancesonic.player.subsonic.SubsonicException
import org.dancesonic.player.subsonic.SubsonicHttp
import org.dancesonic.player.subsonic.streamUrl

private const val TAG = "DanceSonicApp"

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "dancesonic_settings")

class DanceSonicApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this, settingsDataStore)
    }
}

/**
 * Manual dependency container. Small enough that a DI framework is not yet warranted.
 */
class AppContainer(context: Context, dataStore: DataStore<Preferences>) {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Current server settings, kept in memory for the HTTP interceptor. Written only by [settingsStore]'s flow. */
    @Volatile
    var currentSettings: ServerSettings? = null
        private set

    val settingsStore = ServerSettingsStore(dataStore)

    /** Shared client for media streaming; sends requests to the configured server with the API key. */
    val httpClient: OkHttpClient = SubsonicHttp.okHttpClient { currentSettings }

    /** Played audio kept on disk. Created on first playback, so the directory is opened only when it is needed. */
    val audioCache: AudioCache by lazy { AudioCache(context) }

    /** File sizes of the queued songs, read by the stream source to bound its requests. */
    val streamLengths = StreamLengths()

    /** Downloads a track into the audio cache when it starts, so playback reads it once. */
    @Suppress("UnsafeOptInUsageError") // Media3 stream classes are marked unstable; see PlayerController.
    private val audioPrefetcher: AudioPrefetcher by lazy {
        AudioPrefetcher(
            audioCache,
            StreamDataSource.Factory(OkHttpDataSource.Factory(httpClient), sizeOf = streamLengths::sizeOf),
            appScope,
        )
    }

    val database: DanceSonicDatabase = DanceSonicDatabase.create(context)

    /** Sync points (change-feed position, last check). Not secret; cleared on disconnect. */
    val syncState = SyncStateStore(dataStore)

    /** The DanceBrainz address. Written only by [danceBrainzSettings]'s flow. */
    @Volatile
    var currentDanceBrainzUrl: HttpUrl = DanceBrainzClient.DEFAULT_BASE_URL
        private set

    val danceBrainzSettings = DanceBrainzSettingsStore(dataStore)

    /** The DanceBrainz sign-in token, encrypted. Empty until the user signs in. */
    val danceBrainzTokens = DanceBrainzTokenStore(dataStore)

    val library =
        LibraryRepository(
            database,
            DanceBrainzSource { currentDanceBrainzUrl },
            syncState = syncState,
            timings = if (BuildConfig.DEBUG) LogcatSyncTimings() else SyncTimings.None,
        )
    val playlists = PlaylistRepository(database)

    /** The playback choices, kept in memory so the player reads them without waiting for the store. */
    @Volatile
    var currentPlaybackSettings: PlaybackSettings = PlaybackSettings()
        private set

    val playbackSettingsStore = PlaybackSettingsStore(dataStore)

    val player =
        PlayerController(
            context,
            appScope,
            { currentSettings },
            playbackSettings = { currentPlaybackSettings },
            onTrackStarted = ::refreshPlayingTrack,
            onQueued = ::onQueued,
        )

    /** Records the queue's sizes, then downloads the track that begins when its size is known. */
    private fun onQueued(
        queue: List<TrackEntity>,
        startIndex: Int,
        settings: ServerSettings,
    ) {
        streamLengths.remember(queue)
        val first = queue.getOrNull(startIndex) ?: return
        first.sizeBytes?.let { audioPrefetcher.prefetch(first.id, settings.streamUrl(first.id), it) }
    }

    private var cachedCatalogClient: SubsonicClient? = null

    /**
     * Makes [client] the catalog client when it is for the same server and key. The connect check uses it,
     * so its extension list is not asked for again by the sync that follows.
     */
    @Synchronized
    fun adoptCatalogClient(client: SubsonicClient) {
        cachedCatalogClient = client
    }

    /**
     * The client for catalog sync. Reused while the server and key are unchanged, so the server's extension
     * list is asked for once rather than on every Refresh.
     */
    @Synchronized
    fun catalogClient(settings: ServerSettings): SubsonicClient {
        val cached = cachedCatalogClient ?: return SubsonicClient(settings).also { cachedCatalogClient = it }
        if (cached.settings.baseUrl == settings.baseUrl && cached.settings.apiKey == settings.apiKey) return cached
        return SubsonicClient(settings).also { cachedCatalogClient = it }
    }

    /**
     * Compares a track that just started with the server, in the background. Playback never waits for it,
     * and a failed check only leaves the stored rows as they were.
     */
    private fun refreshPlayingTrack(songId: String) {
        val settings = currentSettings ?: return
        appScope.launch {
            try {
                library.refreshTrack(SubsonicClient(settings), songId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: SubsonicException) {
                // Deliberately not shown: the stored track still plays, and the next sync corrects it.
                Log.d(TAG, "Play-time refresh skipped: ${e.javaClass.simpleName}")
            }
        }
    }

    init {
        settingsStore.settings
            .onEach { currentSettings = it }
            .launchIn(appScope)
        danceBrainzSettings.baseUrl
            .onEach { currentDanceBrainzUrl = it }
            .launchIn(appScope)
        playbackSettingsStore.settings
            .onEach {
                currentPlaybackSettings = it
                player.settingsChanged()
            }
            .launchIn(appScope)
    }
}
