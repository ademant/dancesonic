package org.dancesonic.player

import android.app.Application
import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import okhttp3.OkHttpClient
import org.dancesonic.player.dance.NoDanceMetadataSource
import org.dancesonic.player.data.LibraryRepository
import org.dancesonic.player.data.PlaylistRepository
import org.dancesonic.player.data.db.DanceSonicDatabase
import org.dancesonic.player.playback.PlayerController
import org.dancesonic.player.security.ServerSettingsStore
import org.dancesonic.player.subsonic.ServerSettings
import org.dancesonic.player.subsonic.SubsonicHttp

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

    val library = LibraryRepository(database, NoDanceMetadataSource)
    val playlists = PlaylistRepository(database)

    val player = PlayerController(context, appScope) { currentSettings }

    init {
        settingsStore.settings
            .onEach { currentSettings = it }
            .launchIn(appScope)
    }
}
