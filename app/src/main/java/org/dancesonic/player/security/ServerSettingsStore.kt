package org.dancesonic.player.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.dancesonic.player.subsonic.ServerSettings
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AES-256-GCM encryption with a non-exportable key in the Android Keystore.
 * The plaintext API key never touches DataStore.
 */
internal class KeystoreCipher(private val alias: String = KEY_ALIAS) {

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    fun encrypt(plain: String): Pair<String, String> {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val ciphertext = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return encode(cipher.iv) to encode(ciphertext)
    }

    fun decrypt(ivB64: String, ciphertextB64: String): String {
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
    }
}

/**
 * Persists the server URL (not secret) and the API key (secret, encrypted) in DataStore.
 * The password of the Navidrome account is never stored.
 */
class ServerSettingsStore internal constructor(
    private val dataStore: DataStore<Preferences>,
    private val cipher: KeystoreCipher,
) {
    constructor(dataStore: DataStore<Preferences>) : this(dataStore, KeystoreCipher())

    /** Current settings, or null if not connected or if the key can no longer be decrypted. */
    val settings: Flow<ServerSettings?> = dataStore.data.map { prefs -> read(prefs) }

    suspend fun save(settings: ServerSettings) {
        val (iv, ciphertext) = cipher.encrypt(settings.apiKey)
        dataStore.edit { prefs ->
            prefs[BASE_URL] = settings.baseUrl.toString()
            prefs[API_KEY_IV] = iv
            prefs[API_KEY_CIPHERTEXT] = ciphertext
        }
    }

    suspend fun clear() {
        dataStore.edit { prefs ->
            prefs.remove(BASE_URL)
            prefs.remove(API_KEY_IV)
            prefs.remove(API_KEY_CIPHERTEXT)
        }
    }

    private fun read(prefs: Preferences): ServerSettings? {
        val url = prefs[BASE_URL]?.toHttpUrlOrNull() ?: return null
        val iv = prefs[API_KEY_IV] ?: return null
        val ciphertext = prefs[API_KEY_CIPHERTEXT] ?: return null
        return try {
            ServerSettings(url, cipher.decrypt(iv, ciphertext))
        } catch (e: Exception) {
            // Keystore key lost or data corrupted: treat as not connected; the user must reconnect.
            null
        }
    }

    private companion object {
        val BASE_URL = stringPreferencesKey("server_base_url")
        val API_KEY_IV = stringPreferencesKey("server_api_key_iv")
        val API_KEY_CIPHERTEXT = stringPreferencesKey("server_api_key_ciphertext")
    }
}
