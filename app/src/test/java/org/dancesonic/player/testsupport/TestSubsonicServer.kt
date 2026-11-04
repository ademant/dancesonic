package org.dancesonic.player.testsupport

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
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
        val held =
            HeldCertificate.Builder()
                .commonName("localhost")
                .addSubjectAlternativeName("localhost")
                .addSubjectAlternativeName("127.0.0.1")
                .build()
        server.useHttps(HandshakeCertificates.Builder().heldCertificate(held).build().sslSocketFactory(), false)
        server.start()

        settings = ServerSettings(server.url("/"), "test-api-key")
        val trust = HandshakeCertificates.Builder().addTrustedCertificate(held.certificate).build()
        http =
            OkHttpClient.Builder()
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

    /**
     * What a full catalog sync reads for songs: one search3 page holding the songs of [albumJsons], each with
     * its album's id, then the empty page that ends paging. Takes album responses as the tests already use them.
     */
    fun enqueueSongsOf(vararg albumJsons: String) {
        enqueueJson(songPageOf(*albumJsons))
        enqueueJson(songPageOf())
    }

    fun enqueueError(code: Int) {
        server.enqueue(MockResponse().setResponseCode(code))
    }

    override fun close() {
        server.shutdown()
    }
}

/** A search3 song page holding the songs of each album response in [albumJsons] (album id filled in as albumId). */
fun songPageOf(vararg albumJsons: String): String {
    val songs = albumJsons.flatMap { songsOf(it) }
    return JsonObject(
        mapOf(
            "subsonic-response" to
                JsonObject(
                    mapOf(
                        "status" to JsonPrimitive("ok"),
                        "searchResult3" to JsonObject(mapOf("song" to JsonArray(songs))),
                    ),
                ),
        ),
    ).toString()
}

private fun songsOf(albumJson: String): List<JsonElement> {
    val album =
        Json.parseToJsonElement(albumJson).jsonObject.getValue("subsonic-response").jsonObject
            .getValue("album").jsonObject
    val albumId = album.getValue("id")
    return album["song"]?.jsonArray.orEmpty().map { JsonObject(it.jsonObject + ("albumId" to albumId)) }
}
