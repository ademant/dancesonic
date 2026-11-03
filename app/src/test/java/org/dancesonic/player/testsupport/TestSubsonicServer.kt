package org.dancesonic.player.testsupport

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.dancesonic.player.subsonic.ApiKeyInterceptor
import org.dancesonic.player.subsonic.ServerSettings
import org.dancesonic.player.subsonic.SubsonicClient
import org.dancesonic.player.subsonic.SubsonicHttp

/** A local HTTPS Subsonic stand-in. Enqueue JSON bodies in the order the client will request them. */
class TestSubsonicServer : AutoCloseable {
    val server = MockWebServer()
    val settings: ServerSettings
    val client: SubsonicClient
    /** Trusts the test certificate; use for non-Subsonic endpoints such as the account API. */
    val http: OkHttpClient

    init {
        val held = HeldCertificate.Builder()
            .commonName("localhost")
            .addSubjectAlternativeName("localhost")
            .addSubjectAlternativeName("127.0.0.1")
            .build()
        server.useHttps(HandshakeCertificates.Builder().heldCertificate(held).build().sslSocketFactory(), false)
        server.start()

        settings = ServerSettings(server.url("/"), "test-api-key")
        val trust = HandshakeCertificates.Builder().addTrustedCertificate(held.certificate).build()
        http = OkHttpClient.Builder()
            .sslSocketFactory(trust.sslSocketFactory(), trust.trustManager)
            .addInterceptor(ApiKeyInterceptor { settings })
            .build()
        client = SubsonicClient(settings, SubsonicHttp.service(settings.baseUrl, http))
    }

    fun enqueueJson(body: String) {
        server.enqueue(
            MockResponse()
                .addHeader("Content-Type", "application/json")
                .setBody(body),
        )
    }

    fun enqueueError(code: Int) {
        server.enqueue(MockResponse().setResponseCode(code))
    }

    override fun close() {
        server.shutdown()
    }
}
