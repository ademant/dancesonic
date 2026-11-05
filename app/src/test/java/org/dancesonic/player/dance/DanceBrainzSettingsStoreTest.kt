package org.dancesonic.player.dance

import app.cash.turbine.test
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.dancesonic.player.testsupport.newTestContainer
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DanceBrainzSettingsStoreTest {
    private val container = newTestContainer()
    private val store = container.danceBrainzSettings

    @Test
    fun `the public server is used until another one is saved`() =
        runBlocking {
            store.baseUrl.test {
                assertEquals(DanceBrainzClient.DEFAULT_BASE_URL, awaitItem())
            }
        }

    @Test
    fun `a saved server is read back, and reset returns to the public one`() =
        runBlocking {
            val other = "https://dance.example.com/".toHttpUrl()

            store.save(other)
            store.baseUrl.test {
                assertEquals(other, awaitItem())
            }

            store.resetToDefault()
            store.baseUrl.test {
                assertEquals(DanceBrainzClient.DEFAULT_BASE_URL, awaitItem())
            }
        }
}
