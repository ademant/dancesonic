package org.dancesonic.player.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.dancesonic.player.subsonic.ServerAuth
import org.dancesonic.player.subsonic.ServerSettings
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.ProviderException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AES-256-GCM encryption with a non-exportable key in the Android Keystore.
 * The plaintext API key never touches DataStore.
 */
internal interface SecretCipher {
    /** Encrypts [plain]; returns the IV and the ciphertext, both Base64. */
    fun encrypt(plain: String): Pair<String, String>

    /** Reverses [encrypt]. Throws a security exception if the key is gone or the data was changed. */
    fun decrypt(
        ivB64: String,
        ciphertextB64: String,
    ): String
}

internal class KeystoreCipher(private val alias: String = KEY_ALIAS) : SecretCipher {
    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(KEY_SIZE_BITS)
                .build(),
        )
        return generator.generateKey()
    }

    override fun encrypt(plain: String): Pair<String, String> {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val ciphertext = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return encode(cipher.iv) to encode(ciphertext)
    }

    override fun decrypt(
        ivB64: String,
        ciphertextB64: String,
    ): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(GCM_TAG_BITS, decode(ivB64)))
        return String(cipher.doFinal(decode(ciphertextB64)), Charsets.UTF_8)
    }

    private fun encode(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)

    private fun decode(text: String): ByteArray = Base64.decode(text, Base64.NO_WRAP)

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "dancesonic.navidrome.apikey.v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_BITS = 128
        const val KEY_SIZE_BITS = 256
    }
}

/**
 * Persists the server URL (not secret) and the API key (secret, encrypted) in DataStore.
 * The password of the Navidrome account is never stored.
 */
class ServerSettingsStore internal constructor(
    private val dataStore: DataStore<Preferences>,
    private val cipher: SecretCipher,
) {
    constructor(dataStore: DataStore<Preferences>) : this(dataStore, KeystoreCipher())

    /** Current settings, or null if not connected or if the key can no longer be decrypted. */
    val settings: Flow<ServerSettings?> = dataStore.data.map { prefs -> read(prefs) }

    suspend fun save(settings: ServerSettings) {
        val encrypted = (settings.auth as? ServerAuth.ApiKey)?.let { cipher.encrypt(it.key) }
        dataStore.edit { prefs ->
            prefs[BASE_URL] = settings.baseUrl.toString()
            prefs[KEY_IN_HEADER] = settings.keyInHeader
            if (settings.tlsPin != null) prefs[TLS_PIN] = settings.tlsPin else prefs.remove(TLS_PIN)
            when (val auth = settings.auth) {
                is ServerAuth.ApiKey -> {
                    val (iv, ciphertext) = requireNotNull(encrypted)
                    prefs[AUTH_KIND] = KIND_API_KEY
                    prefs[API_KEY_IV] = iv
                    prefs[API_KEY_CIPHERTEXT] = ciphertext
                    prefs.remove(DEVICE_ALIAS)
                }
                is ServerAuth.Device -> {
                    prefs[AUTH_KIND] = KIND_DEVICE
                    prefs[DEVICE_ALIAS] = auth.alias
                    prefs.remove(API_KEY_IV)
                    prefs.remove(API_KEY_CIPHERTEXT)
                }
            }
        }
    }

    suspend fun clear() {
        dataStore.edit { prefs ->
            prefs.remove(BASE_URL)
            prefs.remove(API_KEY_IV)
            prefs.remove(API_KEY_CIPHERTEXT)
            prefs.remove(KEY_IN_HEADER)
            prefs.remove(TLS_PIN)
            prefs.remove(AUTH_KIND)
            prefs.remove(DEVICE_ALIAS)
        }
    }

    private fun read(prefs: Preferences): ServerSettings? {
        val url = prefs[BASE_URL]?.toHttpUrlOrNull() ?: return null
        val auth = readAuth(prefs) ?: return null
        return ServerSettings(
            baseUrl = url,
            auth = auth,
            keyInHeader = prefs[KEY_IN_HEADER] == true,
            tlsPin = prefs[TLS_PIN],
        )
    }

    /** The stored credential, or null when it is missing or the API key can no longer be decrypted. */
    private fun readAuth(prefs: Preferences): ServerAuth? {
        if (prefs[AUTH_KIND] == KIND_DEVICE) return prefs[DEVICE_ALIAS]?.let { ServerAuth.Device(it) }
        val key =
            prefs[API_KEY_IV]?.let { iv ->
                prefs[API_KEY_CIPHERTEXT]?.let { ciphertext -> decryptOrNull(iv, ciphertext) }
            }
        return key?.let { ServerAuth.ApiKey(it) }
    }

    /**
     * Keystore key lost or data corrupted: treat as not connected and let the user reconnect. The
     * failure is expected, so it is reported as "no settings" rather than thrown into the flow.
     */
    @Suppress("SwallowedException")
    private fun decryptOrNull(
        iv: String,
        ciphertext: String,
    ): String? =
        try {
            cipher.decrypt(iv, ciphertext)
        } catch (e: GeneralSecurityException) {
            null
        } catch (e: ProviderException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        }

    private companion object {
        val BASE_URL = stringPreferencesKey("server_base_url")
        val API_KEY_IV = stringPreferencesKey("server_api_key_iv")
        val API_KEY_CIPHERTEXT = stringPreferencesKey("server_api_key_ciphertext")
        val KEY_IN_HEADER = booleanPreferencesKey("server_key_in_header")
        val TLS_PIN = stringPreferencesKey("server_tls_pin")
        val AUTH_KIND = stringPreferencesKey("server_auth_kind")
        val DEVICE_ALIAS = stringPreferencesKey("server_device_alias")
        const val KIND_API_KEY = "api_key"
        const val KIND_DEVICE = "device"
    }
}
