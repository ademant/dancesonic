package org.dancesonic.player.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first

/**
 * Sync points kept between runs: which server the catalog came from, the change-feed point of the
 * last applied sync, and when an incremental check last ran. Not secret, so it lives beside the
 * settings without encryption. Every value is tied to one server URL and ignored for any other.
 */
class SyncStateStore(private val dataStore: DataStore<Preferences>) {
    /** Change-feed point (epoch ms) for [serverUrl], or null when there is none for that server. */
    suspend fun serverTime(serverUrl: String): Long? {
        val prefs = dataStore.data.first()
        return if (prefs[SERVER_URL] == serverUrl) prefs[SERVER_TIME] else null
    }

    /** Epoch ms of the last incremental check for [serverUrl], or null if none ran. */
    suspend fun lastCheck(serverUrl: String): Long? {
        val prefs = dataStore.data.first()
        return if (prefs[SERVER_URL] == serverUrl) prefs[LAST_CHECK] else null
    }

    /** Stores the change-feed point and the check time. A null [serverTime] keeps the stored one. */
    suspend fun save(
        serverUrl: String,
        serverTime: Long?,
        checkedAt: Long,
    ) {
        dataStore.edit { prefs ->
            if (prefs[SERVER_URL] != serverUrl) {
                prefs.remove(SERVER_TIME)
                prefs.remove(LAST_FULL)
            }
            prefs[SERVER_URL] = serverUrl
            if (serverTime != null) prefs[SERVER_TIME] = serverTime
            prefs[LAST_CHECK] = checkedAt
        }
    }

    /**
     * Forgets every sync point, for example on disconnect. The dance source is kept, because it describes the
     * stored DanceBrainz lookups.
     */
    suspend fun clear() {
        dataStore.edit { prefs ->
            prefs.remove(SERVER_URL)
            prefs.remove(SERVER_TIME)
            prefs.remove(LAST_CHECK)
            prefs.remove(LAST_FULL)
        }
    }

    /** The source key the stored DanceBrainz lookups came from, or null if none were recorded. */
    suspend fun danceSource(): String? = dataStore.data.first()[DANCE_SOURCE]

    /** Records that the stored DanceBrainz lookups now come from [sourceKey]. */
    suspend fun saveDanceSource(sourceKey: String) {
        dataStore.edit { prefs -> prefs[DANCE_SOURCE] = sourceKey }
    }

    /** Epoch ms of the last full listing for [serverUrl], or null when none ran. */
    suspend fun lastFull(serverUrl: String): Long? {
        val prefs = dataStore.data.first()
        return if (prefs[SERVER_URL] == serverUrl) prefs[LAST_FULL] else null
    }

    /** Records that a full listing finished for [serverUrl] at [at]. */
    suspend fun markFull(
        serverUrl: String,
        at: Long,
    ) {
        dataStore.edit { prefs ->
            prefs[SERVER_URL] = serverUrl
            prefs[LAST_FULL] = at
        }
    }

    private companion object {
        val SERVER_URL = stringPreferencesKey("sync_server_url")
        val SERVER_TIME = longPreferencesKey("sync_server_time")
        val LAST_CHECK = longPreferencesKey("sync_last_check")
        val LAST_FULL = longPreferencesKey("sync_last_full")
        val DANCE_SOURCE = stringPreferencesKey("sync_dance_source")
    }
}
