package org.dancesonic.player.dance

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import org.dancesonic.player.testsupport.TestSubsonicServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.HttpException

class DanceBrainzClientTest {
    @Test
    fun `sends one anonymous batch request with the recording ids`() {
        TestSubsonicServer().use { server ->
            server.server.enqueue(json(EMPTY_ANSWER))
            val client = DanceBrainzClient(server.server.url("/"), server.plain)

            runBlocking { client.lookup(listOf(MBID)) }

            val request = server.server.takeRequest()
            assertEquals("POST", request.method)
            assertEquals("/api/v1/recordings/lookup", request.requestUrl?.encodedPath)
            assertNull(request.getHeader("Authorization"))
            assertNull(request.requestUrl?.queryParameter("apiKey"))
            assertTrue(request.body.readUtf8().contains("\"mbids\":[\"$MBID\"]"))
        }
    }

    @Test
    fun `keeps only dance tags and reports tempo and raters`() {
        TestSubsonicServer().use { server ->
            server.server.enqueue(json(ANSWER_WITH_TAGS))
            val client = DanceBrainzClient(server.server.url("/"), server.plain)

            val answer = runBlocking { client.lookup(listOf(MBID)) }[MBID]

            assertEquals(listOf("7pas"), answer?.danceTypes)
            assertEquals(118, answer?.bpm)
            assertEquals(4, answer?.raters)
        }
    }

    @Test
    fun `an untagged recording has no dance types and no tempo`() {
        TestSubsonicServer().use { server ->
            server.server.enqueue(json(EMPTY_ANSWER))
            val client = DanceBrainzClient(server.server.url("/"), server.plain)

            val answer = runBlocking { client.lookup(listOf(MBID)) }[MBID]

            assertEquals(emptyList<String>(), answer?.danceTypes)
            assertNull(answer?.bpm)
            assertEquals(0, answer?.raters)
        }
    }

    @Test
    fun `a tempo outside DanceBrainz's range is dropped`() {
        TestSubsonicServer().use { server ->
            server.server.enqueue(json(ANSWER_WITH_BAD_TEMPO))
            val client = DanceBrainzClient(server.server.url("/"), server.plain)

            val answer = runBlocking { client.lookup(listOf(MBID)) }[MBID]

            assertNull(answer?.bpm)
        }
    }

    @Test
    fun `a recording missing from the answer is absent`() {
        TestSubsonicServer().use { server ->
            server.server.enqueue(json(EMPTY_ANSWER))
            val client = DanceBrainzClient(server.server.url("/"), server.plain)

            val answers = runBlocking { client.lookup(listOf("other-mbid")) }

            assertFalse(answers.containsKey("other-mbid"))
        }
    }

    @Test
    fun `a rate-limited batch throws so the caller can count it`() {
        TestSubsonicServer().use { server ->
            server.server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "2"))
            val client = DanceBrainzClient(server.server.url("/"), server.plain)

            assertThrows(HttpException::class.java) {
                runBlocking { client.lookup(listOf(MBID)) }
            }
        }
    }

    @Test
    fun `a malformed answer throws`() {
        TestSubsonicServer().use { server ->
            server.server.enqueue(json("not json"))
            val client = DanceBrainzClient(server.server.url("/"), server.plain)

            assertThrows(Exception::class.java) {
                runBlocking { client.lookup(listOf(MBID)) }
            }
        }
    }

    @Test
    fun `a batch larger than the server accepts is refused before any request`() {
        TestSubsonicServer().use { server ->
            val client = DanceBrainzClient(server.server.url("/"), server.plain)
            val tooMany = List(DanceMetadataSource.MAX_BATCH_SIZE + 1) { "id-$it" }

            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { client.lookup(tooMany) }
            }
            assertEquals(0, server.server.requestCount)
        }
    }

    private fun json(body: String): MockResponse =
        MockResponse()
            .addHeader("Content-Type", "application/json")
            .setBody(body)

    private companion object {
        const val MBID = "7bd7f049-11ba-4e9b-90f0-570b81c62c8c"

        const val EMPTY_ANSWER = """{"recordings":[{"mbid":"$MBID","tags":[],"bpm":null,"raters":0}]}"""

        // The live server sends `path` as objects; the client must ignore it and read `type` and `name`.
        const val ANSWER_WITH_TAGS =
            """{"recordings":[{"mbid":"$MBID","tags":[""" +
                """{"tag_id":142,"type":"dance","name":"7pas",""" +
                """"path":[{"id":142,"name":"7pas"}],"qualifier":"both"},""" +
                """{"tag_id":7,"type":"region","name":"Alsace","path":[{"id":7,"name":"Alsace"}]}""" +
                """],"bpm":118,"raters":4}]}"""

        const val ANSWER_WITH_BAD_TEMPO = """{"recordings":[{"mbid":"$MBID","tags":[],"bpm":900,"raters":1}]}"""
    }
}
