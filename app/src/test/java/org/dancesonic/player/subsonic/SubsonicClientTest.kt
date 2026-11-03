package org.dancesonic.player.subsonic

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SubsonicClientTest {
    private lateinit var server: MockWebServer
    private lateinit var settings: ServerSettings
    private lateinit var client: SubsonicClient

    @Before
    fun setUp() {
        val held =
            HeldCertificate.Builder()
                .commonName("localhost")
                .addSubjectAlternativeName("localhost")
                .addSubjectAlternativeName("127.0.0.1")
                .build()
        val serverCerts = HandshakeCertificates.Builder().heldCertificate(held).build()
        val clientCerts = HandshakeCertificates.Builder().addTrustedCertificate(held.certificate).build()

        server =
            MockWebServer().apply {
                useHttps(serverCerts.sslSocketFactory(), false)
                start()
            }

        settings = ServerSettings(server.url("/"), API_KEY)

        val okHttp =
            OkHttpClient.Builder()
                .sslSocketFactory(clientCerts.sslSocketFactory(), clientCerts.trustManager)
                .addInterceptor(ApiKeyInterceptor { settings })
                .build()
        client = SubsonicClient(settings, SubsonicHttp.service(settings.baseUrl, okHttp))
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `every request carries the API key and no password-based parameters`() =
        runBlocking {
            server.enqueue(okJson(PING_OK))
            server.enqueue(okJson(EXTENSIONS_OK))
            client.checkConnection()

            assertKeyOnly(server.takeRequest())
            assertKeyOnly(server.takeRequest())
        }

    @Test
    fun `connection check passes when apiKeyAuthentication is advertised`() =
        runBlocking {
            server.enqueue(okJson(PING_OK))
            server.enqueue(okJson(EXTENSIONS_OK))
            client.checkConnection()

            assertEquals("/rest/ping.view", server.takeRequest().requestUrl!!.encodedPath)
            assertEquals("/rest/getOpenSubsonicExtensions.view", server.takeRequest().requestUrl!!.encodedPath)
        }

    @Test
    fun `connection check fails when the server does not advertise API key login`() =
        runBlocking {
            server.enqueue(okJson(PING_OK))
            server.enqueue(okJson(EXTENSIONS_WITHOUT_API_KEY))
            val error =
                assertThrows(SubsonicException::class.java) {
                    runBlocking { client.checkConnection() }
                }
            assertTrue(error.message!!.contains("does not support API key login"))
        }

    @Test
    fun `failed status is surfaced with the server error code`() =
        runBlocking {
            server.enqueue(okJson(FAILED_WRONG_KEY))
            val error =
                assertThrows(SubsonicException::class.java) {
                    runBlocking { client.checkConnection() }
                }
            assertEquals(44, error.code)
        }

    @Test
    fun `http error statuses become SubsonicException`() =
        runBlocking {
            server.enqueue(MockResponse().setResponseCode(500).setBody("boom"))
            val error =
                assertThrows(SubsonicException::class.java) {
                    runBlocking { client.checkConnection() }
                }
            assertNull(error.code)
        }

    @Test
    fun `artists are flattened from the index and optional fields may be absent`() =
        runBlocking {
            server.enqueue(okJson(ARTISTS))
            val artists = client.artists()
            assertEquals(listOf("ar-1", "ar-2"), artists.map { it.id })
            assertEquals("Ada", artists.first().name)
        }

    @Test
    fun `album songs expose the recording MBID when the server provides it`() =
        runBlocking {
            server.enqueue(okJson(ALBUM))
            val album = client.album("al-1")
            assertEquals("rec-mbid-1", album.song[0].musicBrainzId)
            assertNull(album.song[1].musicBrainzId)
        }

    @Test
    fun `album list is read page by page until an empty page`() =
        runBlocking<Unit> {
            server.enqueue(okJson(ALBUM_LIST_PAGE_1))
            server.enqueue(okJson(ALBUM_LIST_PAGE_2))
            server.enqueue(okJson("""{"subsonic-response":{"status":"ok","albumList2":{"album":[]}}}"""))

            val albums = client.allAlbums()

            assertEquals(listOf("al-1", "al-2", "al-3"), albums.map { it.id })
            val offsets = List(3) { server.takeRequest().requestUrl!!.queryParameter("offset") }
            assertEquals(listOf("0", "2", "3"), offsets)
        }

    @Test
    fun `stream URL contains no API key`() {
        val url = client.streamUrl("song-42").toString()
        assertTrue(url.endsWith("/rest/stream.view?id=song-42"))
        assertFalse(url.contains(API_KEY))
        assertFalse(url.contains("apiKey"))
    }

    @Test
    fun `server URL must be https`() {
        assertThrows(IllegalArgumentException::class.java) {
            ServerUrl.parse("http://navidrome.local/")
        }
        val parsed = ServerUrl.parse("https://navidrome.example/music")
        assertEquals("https://navidrome.example/music/", parsed.toString())
    }

    @Test
    fun `settings toString never reveals the API key`() {
        assertFalse(settings.toString().contains(API_KEY))
    }

    private fun assertKeyOnly(request: RecordedRequest) {
        val params = request.requestUrl!!.queryParameterNames
        assertTrue("apiKey missing", params.contains("apiKey"))
        assertEquals(API_KEY, request.requestUrl!!.queryParameter("apiKey"))
        for (forbidden in listOf("u", "p", "t", "s")) {
            assertFalse("$forbidden must not be sent with apiKey", params.contains(forbidden))
        }
        assertEquals("json", request.requestUrl!!.queryParameter("f"))
        assertEquals("DanceSonic", request.requestUrl!!.queryParameter("c"))
    }

    private fun okJson(body: String) =
        MockResponse()
            .addHeader("Content-Type", "application/json")
            .setBody(body)

    private companion object {
        const val API_KEY = "test-api-key-123"

        const val PING_OK = """{"subsonic-response":{"status":"ok","version":"1.16.1"}}"""

        const val EXTENSIONS_OK = """{"subsonic-response":{"status":"ok","version":"1.16.1",
            "openSubsonicExtensions":[{"name":"apiKeyAuthentication","versions":[1]}]}}"""

        const val EXTENSIONS_WITHOUT_API_KEY = """{"subsonic-response":{"status":"ok","version":"1.16.1",
            "openSubsonicExtensions":[{"name":"transcodeOffset","versions":[1]}]}}"""

        const val FAILED_WRONG_KEY = """{"subsonic-response":{"status":"failed","version":"1.16.1",
            "error":{"code":44,"message":"Invalid API key"}}}"""

        const val ARTISTS = """{"subsonic-response":{"status":"ok","version":"1.16.1",
            "artists":{"ignoredArticles":"The","index":[
              {"name":"A","artist":[{"id":"ar-1","name":"Ada","albumCount":1}]},
              {"name":"B","artist":[{"id":"ar-2","name":"Bo"}]}
            ]}}}"""

        const val ALBUM_LIST_PAGE_1 = """{"subsonic-response":{"status":"ok","albumList2":{"album":[
            {"id":"al-1","name":"One","artist":"Ada","artistId":"ar-1"},
            {"id":"al-2","name":"Two","artist":"Ada","artistId":"ar-1"}]}}}"""

        const val ALBUM_LIST_PAGE_2 = """{"subsonic-response":{"status":"ok","albumList2":{"album":[
            {"id":"al-3","name":"Three","artist":"Bo","artistId":"ar-2"}]}}}"""

        const val ALBUM = """{"subsonic-response":{"status":"ok","version":"1.16.1",
            "album":{"id":"al-1","name":"Night","artist":"Ada","year":1999,"song":[
              {"id":"s-1","title":"One","track":1,"duration":200,"musicBrainzId":"rec-mbid-1","artist":"Ada"},
              {"id":"s-2","title":"Two","track":2,"duration":180,"artist":"Ada","unknownFutureField":true}
            ]}}}"""
    }
}
