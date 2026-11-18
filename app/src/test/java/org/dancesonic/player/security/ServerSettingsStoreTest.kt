package org.dancesonic.player.security

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.dancesonic.player.subsonic.ServerAuth
import org.dancesonic.player.subsonic.ServerSettings
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
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ServerSettingsStoreTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val file = File(context.cacheDir, "settings-test-${UUID.randomUUID()}.preferences_pb")
    private val dataStore = PreferenceDataStoreFactory.create(produceFile = { file })
    private val store = ServerSettingsStore(dataStore, ReversingCipher())

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
    fun `an http connection keeps its opt-in (#84)`() =
        runBlocking {
            store.save(ServerSettings("http://192.168.1.20:4533/".toHttpUrl(), "key-1", allowCleartext = true))

            val read = store.settings.first()
            assertEquals("http://192.168.1.20:4533/", read?.baseUrl.toString())
            assertTrue(read?.allowCleartext == true)
        }

    @Test
    fun `an http connection stored without the opt-in reads as not connected (#84)`() =
        runBlocking {
            store.save(ServerSettings("http://192.168.1.20:4533/".toHttpUrl(), "key-1", allowCleartext = true))
            // As saved before the opt-in existed: the address is http and no opt-in is stored.
            dataStore.edit { it.remove(booleanPreferencesKey("server_allow_cleartext")) }

            assertNull(store.settings.first())
        }

    @Test
    fun `an https connection is stored without the opt-in by default`() =
        runBlocking {
            store.save(ServerSettings("https://music.example.com/".toHttpUrl(), "key-1"))

            assertFalse(store.settings.first()?.allowCleartext ?: true)
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
