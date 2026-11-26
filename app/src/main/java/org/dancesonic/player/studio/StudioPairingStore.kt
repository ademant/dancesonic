package org.dancesonic.player.studio

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * One paired Studio, as the phone keeps it. Everything here is public or an id: the device key is in the Keystore,
 * under [keyAlias]. [counter] is the last counter the phone used with this Studio; it only goes up.
 */
@Serializable
data class StudioPairing(
    /** A local id for the app's lists; not sent to Studio. */
    val id: String,
    val name: String,
    /** Studio's fingerprint, base64url. */
    val fingerprint: String,
    /** Studio's Noise public key, base64url. Needed to check Studio on every connection. */
    val studioNoiseKey: String,
    /** Studio's BLE service UUID, kept for the Bluetooth transport. */
    val bleService: String,
    /** The device id Studio gave the phone. */
    val deviceId: String,
    /** The Keystore alias of the phone's device key for this Studio. */
    val keyAlias: String,
    /** The phone's Noise public key, as registered with Studio. */
    val phoneNoiseKey: String,
    val counter: Long = 0,
    /** IV of the phone's Noise private key, Base64. Null for pairings made before Bluetooth. */
    val phoneNoiseIv: String? = null,
    /** The phone's Noise private key, encrypted with the Keystore key, Base64. Null for old pairings. */
    val phoneNoiseSealed: String? = null,
    /** The addresses Studio last reported over Bluetooth, tried when mDNS finds nothing. Empty until then. */
    val hintAddresses: List<String> = emptyList(),
    /** The HTTPS port Studio last reported with [hintAddresses]. */
    val hintPort: Int? = null,
    /** When the phone last opened a session with this Studio, epoch milliseconds. Null until it has (#88). */
    val lastConnectedAtMs: Long? = null,
    /** The transport of that session: `ble`, `relay` or `network`, as Studio named it. */
    val lastTransport: String? = null,
) {
    /**
     * The transports this pairing can use, in the order they are tried. The network is always possible; Bluetooth and
     * folksonic's relay need the phone's Noise key, which pairings made before they existed lack. The relay also needs
     * a folksonic connection, which is checked when connecting.
     */
    fun transports(): List<String> =
        StudioTransports.initial().filter { transport ->
            when (transport) {
                StudioTransports.BLUETOOTH -> phoneNoiseSealed != null && isUuid(bleService)
                StudioTransports.RELAY -> phoneNoiseSealed != null
                else -> true
            }
        }

    private fun isUuid(text: String): Boolean = runCatching { java.util.UUID.fromString(text) }.isSuccess
}

/**
 * The paired Studios, kept in the settings DataStore as one JSON list. Not secret, so it is not encrypted; the keys
 * stay in the Keystore.
 */
class StudioPairingStore(
    private val dataStore: DataStore<Preferences>,
) {
    /** Every pairing, in the order they were added. */
    val pairings: Flow<List<StudioPairing>> = dataStore.data.map { decode(it[KEY]) }

    suspend fun list(): List<StudioPairing> = pairings.first()

    suspend fun find(id: String): StudioPairing? = list().firstOrNull { it.id == id }

    suspend fun add(pairing: StudioPairing) {
        dataStore.edit { prefs -> prefs[KEY] = encode(decode(prefs[KEY]) + pairing) }
    }

    suspend fun rename(
        id: String,
        name: String,
    ) {
        dataStore.edit { prefs ->
            prefs[KEY] = encode(decode(prefs[KEY]).map { if (it.id == id) it.copy(name = name) else it })
        }
    }

    /** Keeps the addresses Studio reported for [id]. A pairing that is gone is left alone. */
    suspend fun saveHint(
        id: String,
        addresses: List<String>,
        port: Int,
    ) {
        dataStore.edit { prefs ->
            prefs[KEY] =
                encode(
                    decode(prefs[KEY]).map {
                        if (it.id == id) it.copy(hintAddresses = addresses, hintPort = port) else it
                    },
                )
        }
    }

    /**
     * Removes a pairing from the list, and forgets it as the last Studio played on. Its key is removed by the caller.
     */
    suspend fun remove(id: String) {
        dataStore.edit { prefs ->
            prefs[KEY] = encode(decode(prefs[KEY]).filterNot { it.id == id })
            if (prefs[LAST_ACTIVE] == id) prefs.remove(LAST_ACTIVE)
        }
    }

    /** Records that a session with [id] opened over [transport] at [atMs]. A pairing that is gone is left alone. */
    suspend fun markConnected(
        id: String,
        transport: String,
        atMs: Long,
    ) {
        dataStore.edit { prefs ->
            prefs[KEY] =
                encode(
                    decode(prefs[KEY]).map {
                        if (it.id == id) it.copy(lastConnectedAtMs = atMs, lastTransport = transport) else it
                    },
                )
        }
    }

    /**
     * The Studio last chosen with "Play on", or null when the phone was chosen (#88). Remembered across restarts so the
     * list can point to it; the phone never reconnects to it by itself.
     */
    val lastActive: Flow<String?> = dataStore.data.map { it[LAST_ACTIVE] }

    /** Remembers [id] as the Studio last played on, or forgets it when [id] is null. */
    suspend fun setLastActive(id: String?) {
        dataStore.edit { prefs -> if (id == null) prefs.remove(LAST_ACTIVE) else prefs[LAST_ACTIVE] = id }
    }

    /**
     * Stores the next counter for [id] and returns it. The value is written before the caller sends anything with it,
     * so a restart never reuses a counter. Returns null when the pairing is gone.
     */
    suspend fun advanceCounter(id: String): Long? {
        var next: Long? = null
        dataStore.edit { prefs ->
            val updated =
                decode(prefs[KEY]).map {
                    if (it.id == id) it.copy(counter = it.counter + 1).also { p -> next = p.counter } else it
                }
            prefs[KEY] = encode(updated)
        }
        return next
    }

    /**
     * The phone's device id on folksonic's relay (#104): made once, then kept, so Studio sees the same phone each
     * time. Random, so it tells folksonic nothing about the phone.
     */
    suspend fun relayPhoneId(): String {
        var id: String? = null
        dataStore.edit { prefs ->
            id = prefs[RELAY_ID] ?: RelayWire.newPhoneId().also { prefs[RELAY_ID] = it }
        }
        return checkNotNull(id)
    }

    private fun decode(text: String?): List<StudioPairing> =
        text?.let { runCatching { json.decodeFromString(ListSerializer(StudioPairing.serializer()), it) }.getOrNull() }
            ?: emptyList()

    private fun encode(list: List<StudioPairing>): String =
        json.encodeToString(
            ListSerializer(StudioPairing.serializer()),
            list,
        )

    private companion object {
        val KEY = stringPreferencesKey("studio_pairings")
        val RELAY_ID = stringPreferencesKey("studio_relay_phone_id")
        val LAST_ACTIVE = stringPreferencesKey("studio_last_active")
        val json = Json { ignoreUnknownKeys = true }
    }
}
