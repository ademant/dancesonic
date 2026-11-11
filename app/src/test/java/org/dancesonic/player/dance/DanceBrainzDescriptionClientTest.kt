package org.dancesonic.player.dance

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import org.dancesonic.player.testsupport.TestSubsonicServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DanceBrainzDescriptionClientTest {
    private val mbid = "7f9c2a1e-0d3b-4c5a-9e8f-1a2b3c4d5e6f"

    @Test
    fun `a stored description is reported, read without a token`() {
        TestSubsonicServer().use { server ->
            server.server.enqueue(
                MockResponse().setResponseCode(
                    200,
                ).setBody("""{"recording":"$mbid","text":"Walk, walk","updated_at":"x"}"""),
            )
            val client = DanceBrainzDescriptionClient(server.server.url("/"), server.plain)

            assertTrue(runBlocking { client.hasDescription(mbid) })

            val request = server.server.takeRequest()
            assertEquals("/api/v1/recordings/$mbid/description", request.requestUrl?.encodedPath)
            assertEquals(null, request.getHeader("Authorization"))
        }
    }

    @Test
    fun `no stored description is a plain no, not an error`() {
        TestSubsonicServer().use { server ->
            server.server.enqueue(MockResponse().setResponseCode(404).setBody("""{"error":"not_found"}"""))
            val client = DanceBrainzDescriptionClient(server.server.url("/"), server.plain)

            assertFalse(runBlocking { client.hasDescription(mbid) })
        }
    }

    @Test
    fun `a server failure is thrown, so the caller can keep its last answer`() {
        TestSubsonicServer().use { server ->
            server.server.enqueue(MockResponse().setResponseCode(500))
            val client = DanceBrainzDescriptionClient(server.server.url("/"), server.plain)

            val error =
                assertThrows(DanceBrainzException::class.java) {
                    runBlocking { client.hasDescription(mbid) }
                }

            assertEquals(500, error.httpCode)
        }
    }
}
