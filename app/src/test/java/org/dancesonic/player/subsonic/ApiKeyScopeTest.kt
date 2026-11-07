package org.dancesonic.player.subsonic

import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/** Which requests the shared client sends the API key with: only the configured origin's Subsonic REST API. */
class ApiKeyScopeTest {
    private lateinit var server: MockWebServer
    private lateinit var otherPort: MockWebServer
    private lateinit var settings: ServerSettings
    private lateinit var client: OkHttpClient

    @Before
    fun setUp() {
        val held =
            HeldCertificate.Builder()
                .commonName("localhost")
                .addSubjectAlternativeName("localhost")
                .addSubjectAlternativeName("127.0.0.1")
                .build()
        val serverCerts = HandshakeCertificates.Builder().heldCertificate(held).build()
        server =
            MockWebServer().apply {
                useHttps(serverCerts.sslSocketFactory(), false)
                start()
            }
        otherPort =
            MockWebServer().apply {
                useHttps(serverCerts.sslSocketFactory(), false)
                start()
            }

        settings = ServerSettings(server.url("/"), API_KEY)
        val trust = HandshakeCertificates.Builder().addTrustedCertificate(held.certificate).build()
        client =
            OkHttpClient.Builder()
                .sslSocketFactory(trust.sslSocketFactory(), trust.trustManager)
                .addInterceptor(ApiKeyInterceptor { settings })
                .build()
    }

    @After
    fun tearDown() {
        server.shutdown()
        otherPort.shutdown()
    }

    @Test
    fun `a REST request on the configured origin gets the key once, with the client parameters`() {
        val request = send(server, server.url("/rest/ping.view"))

        assertEquals(listOf(API_KEY), request.requestUrl!!.queryParameterValues("apiKey"))
        assertEquals(listOf("DanceSonic"), request.requestUrl!!.queryParameterValues("c"))
        assertEquals(listOf("json"), request.requestUrl!!.queryParameterValues("f"))
    }

    @Test
    fun `the stream URL under the REST API still authenticates`() {
        val request = send(server, settings.streamUrl("song-1"))

        assertEquals(listOf(API_KEY), request.requestUrl!!.queryParameterValues("apiKey"))
        assertEquals(listOf("song-1"), request.requestUrl!!.queryParameterValues("id"))
    }

    @Test
    fun `a different host gets no key`() {
        val url = server.url("/rest/ping.view").newBuilder().host("127.0.0.1").build()

        assertNull(send(server, url).requestUrl!!.queryParameter("apiKey"))
    }

    @Test
    fun `a different port on the same host gets no key`() {
        val request = send(otherPort, otherPort.url("/rest/ping.view"))

        assertNull(request.requestUrl!!.queryParameter("apiKey"))
    }

    @Test
    fun `a path outside the REST API gets no key`() {
        val request = send(server, server.url("/api/keys"))

        assertNull(request.requestUrl!!.queryParameter("apiKey"))
    }

    @Test
    fun `an authentication parameter already on the request is replaced, not duplicated`() {
        val url = server.url("/rest/ping.view").newBuilder().addQueryParameter("apiKey", "stale").build()
        val request = send(server, url)

        assertEquals(listOf(API_KEY), request.requestUrl!!.queryParameterValues("apiKey"))
    }

    /** Sends one request to [target] and returns what the server received; [host] answers it. */
    private fun send(
        host: MockWebServer,
        target: HttpUrl,
    ): RecordedRequest {
        host.enqueue(MockResponse().setBody("{}"))
        client.newCall(Request.Builder().url(target).build()).execute().close()
        return host.takeRequest()
    }

    private companion object {
        const val API_KEY = "scope-test-key"
    }
}
