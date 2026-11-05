package org.dancesonic.player.dance

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * The DanceBrainz server address. Not secret, so it is kept beside the other settings without encryption.
 * Until one is saved, the public server is used.
 */
class DanceBrainzSettingsStore(private val dataStore: DataStore<Preferences>) {
    /** The configured server, or [DanceBrainzClient.DEFAULT_BASE_URL] when none is saved. */
    val baseUrl: Flow<HttpUrl> =
        dataStore.data.map { prefs ->
            prefs[BASE_URL]?.toHttpUrlOrNull() ?: DanceBrainzClient.DEFAULT_BASE_URL
        }

    /** Saves [url], which must already be https. */
    suspend fun save(url: HttpUrl) {
        dataStore.edit { prefs -> prefs[BASE_URL] = url.toString() }
    }

    /** Goes back to the public server. */
    suspend fun resetToDefault() {
        dataStore.edit { prefs -> prefs.remove(BASE_URL) }
    }

    private companion object {
        val BASE_URL = stringPreferencesKey("dancebrainz_base_url")
    }
}
