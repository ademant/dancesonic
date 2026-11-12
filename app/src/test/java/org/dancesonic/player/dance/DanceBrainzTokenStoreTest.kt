package org.dancesonic.player.dance

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import kotlinx.coroutines.runBlocking
import org.dancesonic.player.security.SecretCipher
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.security.GeneralSecurityException
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DanceBrainzTokenStoreTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val file = File(context.cacheDir, "token-test-${UUID.randomUUID()}.preferences_pb")
    private val cipher = ReversingCipher()
    private val dataStore = PreferenceDataStoreFactory.create(produceFile = { file })
    private val store = DanceBrainzTokenStore(dataStore, cipher)

    @After
    fun tearDown() {
        file.delete()
    }

    @Test
    fun `the token is stored encrypted and read back with its id`() =
        runBlocking {
            store.save(DanceBrainzCredential.Token("dbz_stored_token_1234567890", tokenId = 9L))

            store.credential.test {
                val credential = awaitItem() as DanceBrainzCredential.Token
                assertEquals("dbz_stored_token_1234567890", credential.token)
                assertEquals(9L, credential.tokenId)
                cancelAndIgnoreRemainingEvents()
            }
            assertFalse(file.readText().contains("dbz_stored_token"))
        }

    @Test
    fun `a paired device stores only the name of its key`() =
        runBlocking {
            store.save(DanceBrainzCredential.Device("dancesonic.device.abc"))

            store.credential.test {
                assertEquals(DanceBrainzCredential.Device("dancesonic.device.abc"), awaitItem())
                cancelAndIgnoreRemainingEvents()
            }
            assertFalse(file.readText().contains("dbz_"))
        }

    @Test
    fun `a device replaces a stored token`() =
        runBlocking {
            store.save(DanceBrainzCredential.Token("dbz_stored_token_1234567890", tokenId = 9L))
            store.save(DanceBrainzCredential.Device("dancesonic.device.abc"))

            store.credential.test {
                assertEquals(DanceBrainzCredential.Device("dancesonic.device.abc"), awaitItem())
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `a pasted token has no id`() =
        runBlocking {
            store.save(DanceBrainzCredential.Token("dbz_pasted_token_1234567890", tokenId = null))

            store.credential.test {
                assertNull((awaitItem() as DanceBrainzCredential.Token).tokenId)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `clearing signs out`() =
        runBlocking {
            store.save(DanceBrainzCredential.Token("dbz_stored_token_1234567890", tokenId = 9L))
            store.clear()

            store.credential.test {
                assertNull(awaitItem())
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `a token the keystore can no longer decrypt reads as signed out`() =
        runBlocking {
            store.save(DanceBrainzCredential.Token("dbz_stored_token_1234567890", tokenId = 9L))
            val broken =
                DanceBrainzTokenStore(
                    dataStore,
                    object : SecretCipher {
                        override fun encrypt(plain: String) = error("unused")

                        override fun decrypt(
                            ivB64: String,
                            ciphertextB64: String,
                        ): String = throw GeneralSecurityException("key lost")
                    },
                )

            broken.credential.test {
                assertNull(awaitItem())
                cancelAndIgnoreRemainingEvents()
            }
            assertTrue(file.exists())
        }

    /** Reversible stand-in for the Keystore cipher, which Robolectric does not provide. */
    private class ReversingCipher : SecretCipher {
        override fun encrypt(plain: String): Pair<String, String> = "iv" to plain.reversed()

        override fun decrypt(
            ivB64: String,
            ciphertextB64: String,
        ): String = ciphertextB64.reversed()
    }
}
