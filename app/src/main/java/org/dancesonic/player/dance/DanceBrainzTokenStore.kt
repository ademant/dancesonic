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

/** A DanceBrainz device token. [tokenId] is known after pairing, so sign-out can revoke it on the server. */
class DanceBrainzCredential(
    val token: String,
    val tokenId: Long?,
) {
    override fun toString(): String = "DanceBrainzCredential(token=<redacted>, tokenId=$tokenId)"
}

/**
 * Keeps the DanceBrainz token encrypted with its own Keystore key. The token never goes to DataStore in plain
 * text, and it is not part of the Subsonic settings.
 */
class DanceBrainzTokenStore internal constructor(
    private val dataStore: DataStore<Preferences>,
    private val cipher: SecretCipher,
) {
    constructor(dataStore: DataStore<Preferences>) : this(dataStore, KeystoreCipher(KEY_ALIAS))

    /** The stored token, or null when signed out or when the stored token can no longer be decrypted. */
    val credential: Flow<DanceBrainzCredential?> = dataStore.data.map { prefs -> read(prefs) }

    suspend fun save(credential: DanceBrainzCredential) {
        val (iv, ciphertext) = cipher.encrypt(credential.token)
        dataStore.edit { prefs ->
            prefs[IV] = iv
            prefs[CIPHERTEXT] = ciphertext
            if (credential.tokenId != null) prefs[TOKEN_ID] = credential.tokenId.toString() else prefs.remove(TOKEN_ID)
        }
    }

    suspend fun clear() {
        dataStore.edit { prefs ->
            prefs.remove(IV)
            prefs.remove(CIPHERTEXT)
            prefs.remove(TOKEN_ID)
        }
    }

    private fun read(prefs: Preferences): DanceBrainzCredential? {
        val iv = prefs[IV]
        val ciphertext = prefs[CIPHERTEXT]
        val token = if (iv != null && ciphertext != null) decryptOrNull(iv, ciphertext) else null
        return token?.let { DanceBrainzCredential(it, prefs[TOKEN_ID]?.toLongOrNull()) }
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
    }
}
