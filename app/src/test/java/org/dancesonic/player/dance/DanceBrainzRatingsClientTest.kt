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
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DanceBrainzRatingsClientTest {
    private val mbid = "7f9c2a1e-0d3b-4c5a-9e8f-1a2b3c4d5e6f"
    private val ratingsPath = "/api/v1/recordings/$mbid/ratings"
    private val shoesPath = "/api/v1/recordings/$mbid/shoes"

    @Test
    fun `the public median is read without a token`() {
        TestSubsonicServer().use { server ->
            server.server.enqueue(json("""{"recording":"$mbid","hearts":2,"shoes_median":3.5,"shoes_raters":4}"""))
            val client = DanceBrainzRatingsClient(server.server.url("/"), server.plain)

            val ratings = runBlocking { client.ratings(mbid, token = null) }

            val request = server.server.takeRequest()
            assertEquals("GET", request.method)
            assertEquals(ratingsPath, request.requestUrl?.encodedPath)
            assertNull(request.getHeader("Authorization"))
            assertEquals(RecordingRatings(shoesMedian = 3.5, shoesRaters = 4, myShoes = null), ratings)
        }
    }

    @Test
    fun `with a token the viewer's own rating is read too`() {
        TestSubsonicServer().use { server ->
            server.server.enqueue(
                json("""{"recording":"$mbid","hearts":0,"shoes_median":null,"shoes_raters":0,"my_shoes":5}"""),
            )
            val client = DanceBrainzRatingsClient(server.server.url("/"), server.plain)

            val ratings = runBlocking { client.ratings(mbid, token = "dbz_viewer_token_1234567890") }

            assertEquals("Bearer dbz_viewer_token_1234567890", server.server.takeRequest().getHeader("Authorization"))
            assertEquals(5, ratings.myShoes)
            assertNull(ratings.shoesMedian)
        }
    }

    @Test
    fun `setting a rating sends its value with the token`() {
        TestSubsonicServer().use { server ->
            server.server.enqueue(MockResponse().setResponseCode(204))
            val client = DanceBrainzRatingsClient(server.server.url("/"), server.plain)

            runBlocking { client.setShoes(mbid, value = 4, token = "dbz_viewer_token_1234567890") }

            val request = server.server.takeRequest()
            assertEquals("PUT", request.method)
            assertEquals(shoesPath, request.requestUrl?.encodedPath)
            assertEquals("Bearer dbz_viewer_token_1234567890", request.getHeader("Authorization"))
            assertEquals("""{"value":4}""", request.body.readUtf8())
        }
    }

    @Test
    fun `clearing a rating sends a delete with the token`() {
        TestSubsonicServer().use { server ->
            server.server.enqueue(MockResponse().setResponseCode(204))
            val client = DanceBrainzRatingsClient(server.server.url("/"), server.plain)

            runBlocking { client.clearShoes(mbid, token = "dbz_viewer_token_1234567890") }

            val request = server.server.takeRequest()
            assertEquals("DELETE", request.method)
            assertEquals(shoesPath, request.requestUrl?.encodedPath)
            assertEquals("Bearer dbz_viewer_token_1234567890", request.getHeader("Authorization"))
        }
    }

    @Test
    fun `a rating outside 1 to 5 is refused before anything is sent`() {
        TestSubsonicServer().use { server ->
            val client = DanceBrainzRatingsClient(server.server.url("/"), server.plain)

            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { client.setShoes(mbid, value = 6, token = "dbz_viewer_token_1234567890") }
            }

            assertEquals(0, server.server.requestCount)
        }
    }

    @Test
    fun `a server that refuses the token gives a message for the user and the status`() {
        TestSubsonicServer().use { server ->
            server.server.enqueue(MockResponse().setResponseCode(401).setBody("{}"))
            val client = DanceBrainzRatingsClient(server.server.url("/"), server.plain)

            val error =
                assertThrows(DanceBrainzException::class.java) {
                    runBlocking { client.setShoes(mbid, value = 2, token = "dbz_revoked_token_1234567890") }
                }

            assertEquals(401, error.httpCode)
            assertTrue(error.message.orEmpty().contains("not accepted"))
            assertFalse(error.message.orEmpty().contains("dbz_"))
        }
    }

    private fun json(body: String): MockResponse = MockResponse().setResponseCode(200).setBody(body)
}
