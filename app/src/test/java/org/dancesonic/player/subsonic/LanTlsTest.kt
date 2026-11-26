package org.dancesonic.player.subsonic

import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import org.dancesonic.player.testsupport.TestSubsonicServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LanTlsTest {
    private lateinit var server: TestSubsonicServer

    @Before
    fun setUp() {
        LanPins.clear()
        server = TestSubsonicServer()
    }

    @After
    fun tearDown() {
        LanPins.clear()
        server.close()
    }

    private val url get() = server.server.url("/")

    @Test
    fun `a private https server with its verified pin connects`() =
        runBlocking {
            val pin = checkNotNull(LanTls.connectPin(url))
            LanPins.trustForSetup(url, pin)

            server.server.enqueue(MockResponse().setBody("{}"))
            val settings = server.settings.withTlsPin(pin)
            SubsonicHttp.okHttpClient { settings }.newCall(pingRequest()).execute().close()

            assertEquals(1, server.server.requestCount)
        }

    @Test
    fun `reading a pin does not make it active (#126)`() =
        runBlocking {
            val pin = LanTls.connectPin(url)

            assertNotNull(pin)
            assertNull(LanPins.pinFor(url.host, url.port))
            assertThrows(IOException::class.java) {
                SubsonicHttp.okHttpClient { server.settings }.newCall(pingRequest()).execute()
            }
            assertEquals(0, server.server.requestCount)
        }

    @Test
    fun `a setup pin is forgotten when the setup ends without storing it (#126)`() =
        runBlocking {
            val pin = checkNotNull(LanTls.connectPin(url))
            LanPins.trustForSetup(url, pin)

            LanPins.endSetup()

            assertNull(LanPins.pinFor(url.host, url.port))
        }

    @Test
    fun `a pin belongs to its origin, not to the whole host (#126)`() {
        LanPins.remember(server.settings.withTlsPin("stored-pin"))

        assertEquals("stored-pin", LanPins.pinFor("LOCALHOST", url.port))
        assertNull(LanPins.pinFor("localhost", url.port + 1))
    }

    @Test
    fun `disconnecting or switching servers drops the old pin (#126)`() {
        LanPins.remember(server.settings.withTlsPin("stored-pin"))

        LanPins.remember(ServerSettings("https://192.168.1.9/".toHttpUrl(), "key", tlsPin = "other-pin"))
        assertNull(LanPins.pinFor(url.host, url.port))
        assertEquals("other-pin", LanPins.pinFor("192.168.1.9", 443))

        LanPins.remember(null)
        assertNull(LanPins.pinFor("192.168.1.9", 443))
    }

    @Test
    fun `a certificate that does not match the pin is refused`() {
        LanPins.remember(server.settings.withTlsPin("not-the-certificate"))
        val settings = server.settings.withTlsPin("not-the-certificate")

        assertThrows(IOException::class.java) {
            SubsonicHttp.okHttpClient { settings }.newCall(pingRequest()).execute()
        }
        assertEquals(0, server.server.requestCount)
    }

    @Test
    fun `a private server without a pin is refused, not trusted on first use`() {
        assertThrows(IOException::class.java) {
            SubsonicHttp.okHttpClient { server.settings }.newCall(pingRequest()).execute()
        }
        assertEquals(0, server.server.requestCount)
    }

    @Test
    fun `no pin is read for a public or plain http address`() =
        runBlocking {
            assertNull(LanTls.connectPin("https://8.8.8.8/".toHttpUrl()))
            assertNull(LanTls.connectPin("http://192.168.1.5/".toHttpUrl()))
        }

    @Test
    fun `a stored pin for a server is loaded at start`() {
        val pinned = server.settings.withTlsPin("stored-pin")

        LanPins.remember(pinned)

        assertEquals("stored-pin", LanPins.pinFor(url.host, url.port))
        assertEquals("stored-pin", LanPins.savedPinFor(url))
    }

    @Test
    fun `a bare address is taken as https`() {
        assertEquals("https://192.168.1.5:4533/", ServerUrl.parse("192.168.1.5:4533").toString())
    }

    private fun pingRequest(): Request = Request.Builder().url(server.server.url("/rest/ping.view")).build()
}
