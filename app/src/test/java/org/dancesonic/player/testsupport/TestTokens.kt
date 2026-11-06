package org.dancesonic.player.testsupport

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.dancesonic.player.dance.DanceBrainzCredential
import org.dancesonic.player.dance.DanceBrainzTokenStore
import org.dancesonic.player.security.SecretCipher
import java.io.File
import java.util.UUID

/**
 * A token store on its own DataStore file. With [token] given, the store starts signed in with it. The cipher is
 * a pass-through, so these tests do not need the Android Keystore.
 */
internal fun tokenStoreFor(token: String?): DanceBrainzTokenStore {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val file = File(context.cacheDir, "tokens-${UUID.randomUUID()}.preferences_pb")
    val store = DanceBrainzTokenStore(PreferenceDataStoreFactory.create(produceFile = { file }), PassThroughCipher)
    if (token != null) {
        runBlocking { store.save(DanceBrainzCredential(token, tokenId = TOKEN_ID)) }
    }
    return store
}

/** The token id that [tokenStoreFor] stores with its token. */
internal const val TOKEN_ID = 7L

private object PassThroughCipher : SecretCipher {
    override fun encrypt(plain: String): Pair<String, String> = "iv" to plain

    override fun decrypt(
        ivB64: String,
        ciphertextB64: String,
    ): String = ciphertextB64
}
