package org.dancesonic.player.dance

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import org.dancesonic.player.testsupport.TestSubsonicServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DanceBrainzChaptersClientTest {
    private val mbid = "5ec2d0c5-03e3-4d8b-9ad2-0c11a6b7a5d1"
    private val chaptersPath = "/api/v1/recordings/$mbid/chapters"
    private val token = "dbz_viewer_token_1234567890"

    @Test
    fun `lookup answers carry the chapters and their version`() {
        TestSubsonicServer().use { server ->
            server.server.enqueue(
                json(
                    """{"recordings":[{"mbid":"$mbid","tags":[],"bpm":null,"raters":0,"chapters_version":"v1",
                        "chapters":[
                          {"index":1,"start_ms":0,"end_ms":112000,"title":null,
                           "dance":{"tag_id":41,"name":"Ton simpl","path":[{"id":40,"name":"Suite"}]}},
                          {"index":2,"start_ms":112000,"end_ms":null,"title":" ","dance":null}
                        ]}]}""",
                ),
            )
            val client = DanceBrainzClient(server.server.url("/"), server.plain)

            val dance = runBlocking { client.lookup(listOf(mbid)) }.getValue(mbid)

            assertEquals("v1", dance.chaptersVersion)
            assertEquals(
                listOf(
                    DanceChapter(0, 112_000, title = null, danceTagId = 41, danceName = "Ton simpl"),
                    DanceChapter(112_000, null, title = null, danceTagId = null, danceName = null),
                ),
                dance.chapters,
            )
        }
    }

    @Test
    fun `a lookup from a server without chapters has no version and no chapters`() {
        TestSubsonicServer().use { server ->
            server.server.enqueue(json("""{"recordings":[{"mbid":"$mbid","tags":[],"bpm":null,"raters":0}]}"""))
            val client = DanceBrainzClient(server.server.url("/"), server.plain)

            val dance = runBlocking { client.lookup(listOf(mbid)) }.getValue(mbid)

            assertNull(dance.chaptersVersion)
            assertTrue(dance.chapters.isEmpty())
        }
    }

    @Test
    fun `reading needs no token and returns the length and version`() {
        TestSubsonicServer().use { server ->
            server.server.enqueue(
                json("""{"mbid":"$mbid","length_ms":300000,"version":"v2","chapters":[]}"""),
            )
            val client = DanceBrainzChaptersClient(server.server.url("/"), server.plain)

            val list = runBlocking { client.read(mbid) }

            val request = server.server.takeRequest()
            assertEquals("GET", request.method)
            assertEquals(chaptersPath, request.requestUrl?.encodedPath)
            assertNull(request.getHeader("Authorization"))
            assertEquals(ChapterList(version = "v2", lengthMs = 300_000, chapters = emptyList()), list)
        }
    }

    @Test
    fun `a mark sends its start only and the version as If-Match`() {
        TestSubsonicServer().use { server ->
            server.server.enqueue(json("""{"change":{"status":"applied"},"chapters":[]}"""))
            val client = DanceBrainzChaptersClient(server.server.url("/"), server.plain)

            val result =
                runBlocking {
                    client.add(
                        mbid,
                        startMs = 61_500,
                        version = "v2",
                        token = token,
                    )
                }

            val request = server.server.takeRequest()
            assertEquals("POST", request.method)
            assertEquals("\"v2\"", request.getHeader("If-Match"))
            assertEquals("Bearer $token", request.getHeader("Authorization"))
            assertEquals("""{"start_ms":61500}""", request.body.readUtf8())
            assertEquals(false, result.pending)
        }
    }

    @Test
    fun `an update sends only the fields it changes`() {
        TestSubsonicServer().use { server ->
            server.server.enqueue(json("""{"change":{"status":"applied"},"chapters":[]}"""))
            val client = DanceBrainzChaptersClient(server.server.url("/"), server.plain)

            runBlocking {
                client.update(mbid, 112_000, ChapterChange(title = "Ton doubl"), version = "v3", token = token)
            }

            val request = server.server.takeRequest()
            assertEquals("PATCH", request.method)
            assertEquals("$chaptersPath/112000", request.requestUrl?.encodedPath)
            assertEquals("""{"title":"Ton doubl"}""", request.body.readUtf8())
        }
    }

    @Test
    fun `clearing a title and a dance sends nulls, and moving sends the new start`() {
        TestSubsonicServer().use { server ->
            server.server.enqueue(json("""{"change":{"status":"applied"},"chapters":[]}"""))
            server.server.enqueue(json("""{"change":{"status":"applied"},"chapters":[]}"""))
            val client = DanceBrainzChaptersClient(server.server.url("/"), server.plain)

            runBlocking {
                client.update(
                    mbid,
                    112_000,
                    ChapterChange(clearTitle = true, clearDance = true),
                    version = "v3",
                    token = token,
                )
                client.update(mbid, 112_000, ChapterChange(newStartMs = 115_000), version = null, token = token)
            }

            assertEquals("""{"title":null,"tag_id":null}""", server.server.takeRequest().body.readUtf8())
            val move = server.server.takeRequest()
            assertEquals("""{"start_ms":115000}""", move.body.readUtf8())
            assertNull(move.getHeader("If-Match"))
        }
    }

    @Test
    fun `a delete sends the version and a held change reports pending`() {
        TestSubsonicServer().use { server ->
            server.server.enqueue(json("""{"change":{"status":"pending"},"chapters":[]}"""))
            val client = DanceBrainzChaptersClient(server.server.url("/"), server.plain)

            val result = runBlocking { client.delete(mbid, 0, version = "v4", token = token) }

            val request = server.server.takeRequest()
            assertEquals("DELETE", request.method)
            assertEquals("\"v4\"", request.getHeader("If-Match"))
            assertTrue(result.pending)
        }
    }

    @Test
    fun `a refused version is reported with its status and a message for the user`() {
        TestSubsonicServer().use { server ->
            server.server.enqueue(MockResponse().setResponseCode(412).setBody("""{"error":"version_mismatch"}"""))
            val client = DanceBrainzChaptersClient(server.server.url("/"), server.plain)

            val error =
                assertThrows(DanceBrainzException::class.java) {
                    runBlocking { client.delete(mbid, 0, version = "old", token = token) }
                }

            assertEquals(412, error.httpCode)
            assertTrue(error.message.orEmpty().contains("changed on DanceBrainz"))
        }
    }

    private fun json(body: String): MockResponse = MockResponse().setResponseCode(200).setBody(body)
}
