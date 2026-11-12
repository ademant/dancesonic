package org.dancesonic.player.subsonic

import okhttp3.mockwebserver.MockResponse
import org.dancesonic.player.testsupport.TestSubsonicServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ApiKeyPlacementTest {
    private lateinit var server: TestSubsonicServer

    @Before
    fun setUp() {
        server = TestSubsonicServer()
    }

    @After
    fun tearDown() {
        server.close()
    }

    @Test
    fun `a folksonic server gets the key in the header and not in the URL`() {
        val http =
            server.plain.newBuilder().addInterceptor(
                ApiKeyInterceptor { server.settings.withKeyInHeader(true) },
            ).build()
        server.server.enqueue(MockResponse().setBody("{}"))

        http.newCall(okhttp3.Request.Builder().url(server.server.url("/rest/ping.view")).build()).execute().close()

        val request = server.server.takeRequest()
        assertEquals("test-api-key", request.getHeader("X-Api-Key"))
        assertNull(request.requestUrl?.queryParameter("apiKey"))
        assertEquals("DanceSonic", request.requestUrl?.queryParameter("c"))
    }

    @Test
    fun `other servers keep the key in the query`() {
        val http = server.plain.newBuilder().addInterceptor(ApiKeyInterceptor { server.settings }).build()
        server.server.enqueue(MockResponse().setBody("{}"))

        http.newCall(okhttp3.Request.Builder().url(server.server.url("/rest/ping.view")).build()).execute().close()

        val request = server.server.takeRequest()
        assertEquals("test-api-key", request.requestUrl?.queryParameter("apiKey"))
        assertNull(request.getHeader("X-Api-Key"))
    }

    @Test
    fun `a key already in the query is replaced, not duplicated, when sent in the header`() {
        val http =
            server.plain.newBuilder().addInterceptor(
                ApiKeyInterceptor { server.settings.withKeyInHeader(true) },
            ).build()
        server.server.enqueue(MockResponse().setBody("{}"))

        http.newCall(
            okhttp3.Request.Builder().url(server.server.url("/rest/ping.view?apiKey=old")).build(),
        ).execute().close()

        assertNull(server.server.takeRequest().requestUrl?.queryParameter("apiKey"))
    }

    @Test
    fun `the Subsonic client does not follow an https redirect`() {
        assertFalse(SubsonicHttp.okHttpClient { null }.followSslRedirects)
        assertTrue(ServerSettings(server.server.url("/"), "k", keyInHeader = true).keyInHeader)
    }

    @Test
    fun `the key header flag is kept by withKeyInHeader and hidden in toString`() {
        val header = server.settings.withKeyInHeader(true)

        assertTrue(header.keyInHeader)
        assertEquals(server.settings.apiKey, header.apiKey)
        assertFalse(header.toString().contains("test-api-key"))
    }
}
