package org.dancesonic.player.dance

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.dancesonic.player.security.KeystoreCipher
import org.dancesonic.player.security.SecretCipher
import java.security.GeneralSecurityException
import java.security.ProviderException

/**
 * How this phone proves itself to DanceBrainz. A [Token] is the bearer token of an older pairing and keeps working.
 * A [Device] is a key in the Android Keystore that signs each request with a DPoP proof (dancesonic #69).
 */
sealed interface DanceBrainzCredential {
    /** A bearer token. [tokenId] is known after pairing, so sign-out can revoke it on the server. */
    class Token(
        val token: String,
        val tokenId: Long?,
    ) : DanceBrainzCredential {
        override fun toString(): String = "DanceBrainzCredential.Token(token=<redacted>, tokenId=$tokenId)"
    }

    /** A paired device. Its key is the Keystore key named [alias]; no secret is stored. */
    data class Device(val alias: String) : DanceBrainzCredential
}

/**
 * Keeps the DanceBrainz credential. A token is stored encrypted with its own Keystore key; a device stores only the
 * name of its key. Neither goes into the Subsonic settings.
 */
class DanceBrainzTokenStore internal constructor(
    private val dataStore: DataStore<Preferences>,
    private val cipher: SecretCipher,
) {
    constructor(dataStore: DataStore<Preferences>) : this(dataStore, KeystoreCipher(KEY_ALIAS))

    /** The stored credential, or null when signed out or when a stored token can no longer be decrypted. */
    val credential: Flow<DanceBrainzCredential?> = dataStore.data.map { prefs -> read(prefs) }

    suspend fun save(credential: DanceBrainzCredential) {
        val encrypted =
            (credential as? DanceBrainzCredential.Token)?.let { cipher.encrypt(it.token) }
        dataStore.edit { prefs ->
            when (credential) {
                is DanceBrainzCredential.Token -> {
                    val (iv, ciphertext) = requireNotNull(encrypted)
                    prefs[IV] = iv
                    prefs[CIPHERTEXT] = ciphertext
                    if (credential.tokenId != null) {
                        prefs[TOKEN_ID] = credential.tokenId.toString()
                    } else {
                        prefs.remove(TOKEN_ID)
                    }
                    prefs.remove(DEVICE_ALIAS)
                }
                is DanceBrainzCredential.Device -> {
                    prefs.remove(IV)
                    prefs.remove(CIPHERTEXT)
                    prefs.remove(TOKEN_ID)
                    prefs[DEVICE_ALIAS] = credential.alias
                }
            }
        }
    }

    suspend fun clear() {
        dataStore.edit { prefs ->
            prefs.remove(IV)
            prefs.remove(CIPHERTEXT)
            prefs.remove(TOKEN_ID)
            prefs.remove(DEVICE_ALIAS)
        }
    }

    private fun read(prefs: Preferences): DanceBrainzCredential? {
        prefs[DEVICE_ALIAS]?.let { return DanceBrainzCredential.Device(it) }
        val iv = prefs[IV]
        val ciphertext = prefs[CIPHERTEXT]
        val token = if (iv != null && ciphertext != null) decryptOrNull(iv, ciphertext) else null
        return token?.let { DanceBrainzCredential.Token(it, prefs[TOKEN_ID]?.toLongOrNull()) }
    }

    /** A lost Keystore key means the user must sign in again; that is reported as signed out, not thrown. */
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
        const val KEY_ALIAS = "dancesonic.dancebrainz.token.v1"
        val IV = stringPreferencesKey("dancebrainz_token_iv")
        val CIPHERTEXT = stringPreferencesKey("dancebrainz_token_ciphertext")
        val TOKEN_ID = stringPreferencesKey("dancebrainz_token_id")
        val DEVICE_ALIAS = stringPreferencesKey("dancebrainz_device_alias")
    }
}
