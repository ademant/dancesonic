package org.dancesonic.player.security

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.dancesonic.player.subsonic.ServerAuth
import org.dancesonic.player.subsonic.ServerSettings
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ServerSettingsStoreTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val file = File(context.cacheDir, "settings-test-${UUID.randomUUID()}.preferences_pb")
    private val store =
        ServerSettingsStore(PreferenceDataStoreFactory.create(produceFile = { file }), ReversingCipher())

    @After
    fun tearDown() {
        file.delete()
    }

    @Test
    fun `an API key connection is stored encrypted and read back`() =
        runBlocking {
            store.save(ServerSettings("https://music.example.com/".toHttpUrl(), "key-plain-123456", keyInHeader = true))

            store.settings.test {
                val read = awaitItem()!!
                assertEquals("key-plain-123456", read.apiKey)
                assertEquals(ServerAuth.ApiKey("key-plain-123456"), read.auth)
                assertEquals(true, read.keyInHeader)
                cancelAndIgnoreRemainingEvents()
            }
            assertFalse(file.readText().contains("key-plain-123456"))
        }

    @Test
    fun `a paired device stores its key alias and no secret`() =
        runBlocking {
            val pin = "sha256/AIi+HIhAaC/H6hu+vF2gpQ/97rDOTGrt52bobf0fjdM="
            store.save(
                ServerSettings(
                    "https://192.168.1.10:4533/".toHttpUrl(),
                    ServerAuth.Device("device-alias-1"),
                    tlsPin = pin,
                ),
            )

            store.settings.test {
                val read = awaitItem()!!
                assertEquals(ServerAuth.Device("device-alias-1"), read.auth)
                assertNull(read.apiKey)
                assertEquals(pin, read.tlsPin)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `clearing removes a device connection`() =
        runBlocking {
            store.save(ServerSettings("https://music.example.com/".toHttpUrl(), ServerAuth.Device("device-alias-2")))

            store.clear()

            store.settings.test {
                assertNull(awaitItem())
                cancelAndIgnoreRemainingEvents()
            }
        }

    private class ReversingCipher : SecretCipher {
        override fun encrypt(plain: String): Pair<String, String> = "iv" to plain.reversed()

        override fun decrypt(
            ivB64: String,
            ciphertextB64: String,
        ): String = ciphertextB64.reversed()
    }
}
