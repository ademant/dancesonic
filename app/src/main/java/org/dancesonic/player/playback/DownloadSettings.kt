package org.dancesonic.player.playback

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Choices for the offline downloads. Not secret, so stored without encryption. */
data class DownloadSettings(
    /** Downloads of kept tracks may use mobile data. Off by default: they wait for an unmetered connection. */
    val allowMobileData: Boolean = false,
)

/** Reads and writes [DownloadSettings] in DataStore. */
class DownloadSettingsStore(private val dataStore: DataStore<Preferences>) {
    val settings: Flow<DownloadSettings> =
        dataStore.data.map { prefs ->
            DownloadSettings(allowMobileData = prefs[ALLOW_MOBILE_DATA] ?: false)
        }

    suspend fun save(settings: DownloadSettings) {
        dataStore.edit { prefs -> prefs[ALLOW_MOBILE_DATA] = settings.allowMobileData }
    }

    private companion object {
        val ALLOW_MOBILE_DATA = booleanPreferencesKey("download_allow_mobile_data")
    }
}
