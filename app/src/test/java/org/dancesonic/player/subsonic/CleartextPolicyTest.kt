package org.dancesonic.player.subsonic

import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Plain http goes only to the server the user opted in for (#84). */
class CleartextPolicyTest {
    private val server = MockWebServer()
    private val other = MockWebServer()
    private val client: OkHttpClient = CleartextPolicy.configure(OkHttpClient.Builder()).build()

    @Before
    fun setUp() {
        server.start()
        other.start()
    }

    @After
    fun tearDown() {
        CleartextPolicy.remember(null)
        server.shutdown()
        other.shutdown()
    }

    private fun optedIn(url: HttpUrl) = ServerSettings(url, "key-123", allowCleartext = true)

    private fun get(url: HttpUrl): Int = client.newCall(Request.Builder().url(url).build()).execute().use { it.code }

    @Test
    fun `http is refused by default, before anything reaches the server`() {
        assertThrows(CleartextRefusedException::class.java) { get(server.url("/rest/ping.view")) }

        assertEquals(0, server.requestCount)
    }

    @Test
    fun `the opted-in server is reached over http`() {
        server.enqueue(MockResponse().setBody("ok"))
        CleartextPolicy.remember(optedIn(server.url("/")))

        assertEquals(200, get(server.url("/rest/ping.view")))
    }

    @Test
    fun `the opt-in covers only its own server, not another port or host`() {
        CleartextPolicy.remember(optedIn(server.url("/")))

        assertThrows(CleartextRefusedException::class.java) { get(other.url("/")) }
        assertFalse(CleartextPolicy.permits("http://192.168.1.99/".toHttpUrl()))
        assertEquals(0, other.requestCount)
    }

    @Test
    fun `stored settings without the opt-in allow no http`() {
        CleartextPolicy.remember(ServerSettings("https://music.example.com/".toHttpUrl(), "key-123"))

        assertFalse(CleartextPolicy.permits(server.url("/")))
    }

    @Test
    fun `https is always permitted`() {
        assertTrue(CleartextPolicy.permits("https://music.example.com/rest/ping.view".toHttpUrl()))
        assertTrue(CleartextPolicy.permits("https://192.168.1.20/".toHttpUrl()))
    }

    @Test
    fun `a server being connected is reachable during setup only`() {
        server.enqueue(MockResponse().setBody("ok"))

        val code = runBlocking { CleartextPolicy.whileConnecting(server.url("/")) { get(server.url("/api/login")) } }

        assertEquals(200, code)
        assertThrows(CleartextRefusedException::class.java) { get(server.url("/api/login")) }
    }

    @Test
    fun `setup never opens http to a public address`() {
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { CleartextPolicy.whileConnecting("http://music.example.com/".toHttpUrl()) { } }
        }
    }

    @Test
    fun `a redirect from the opted-in server to another http server is not followed`() {
        server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", other.url("/elsewhere")))
        CleartextPolicy.remember(optedIn(server.url("/")))

        assertThrows(CleartextRefusedException::class.java) { get(server.url("/rest/stream.view")) }
        assertEquals(0, other.requestCount)
    }

    @Test
    fun `the Subsonic client refuses an http server that is not opted in, and its key never leaves`() {
        val settings = optedIn(server.url("/"))
        val http = SubsonicHttp.okHttpClient { settings }

        assertThrows(CleartextRefusedException::class.java) {
            http.newCall(Request.Builder().url(settings.streamUrl("song-1")).build()).execute().close()
        }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `the refusal names no address`() {
        val message = CleartextRefusedException().message.orEmpty()

        assertFalse(message.contains("localhost"))
        assertFalse(message.contains("127.0.0.1"))
    }
}
