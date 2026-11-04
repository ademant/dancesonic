package org.dancesonic.player

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import org.dancesonic.player.dance.NoDanceMetadataSource
import org.dancesonic.player.data.LibraryRepository
import org.dancesonic.player.data.LogcatSyncTimings
import org.dancesonic.player.data.PlaylistRepository
import org.dancesonic.player.data.SyncStateStore
import org.dancesonic.player.data.SyncTimings
import org.dancesonic.player.data.db.DanceSonicDatabase
import org.dancesonic.player.playback.PlayerController
import org.dancesonic.player.security.ServerSettingsStore
import org.dancesonic.player.subsonic.ServerSettings
import org.dancesonic.player.subsonic.SubsonicClient
import org.dancesonic.player.subsonic.SubsonicException
import org.dancesonic.player.subsonic.SubsonicHttp

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

    val database: DanceSonicDatabase = DanceSonicDatabase.create(context)

    /** Sync points (change-feed position, last check). Not secret; cleared on disconnect. */
    val syncState = SyncStateStore(dataStore)

    val library =
        LibraryRepository(
            database,
            NoDanceMetadataSource,
            syncState = syncState,
            timings = if (BuildConfig.DEBUG) LogcatSyncTimings() else SyncTimings.None,
        )
    val playlists = PlaylistRepository(database)

    val player =
        PlayerController(context, appScope, { currentSettings }, onTrackStarted = ::refreshPlayingTrack)

    private var cachedCatalogClient: SubsonicClient? = null

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
    }
}
