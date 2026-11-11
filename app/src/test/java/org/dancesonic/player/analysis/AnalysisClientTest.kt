package org.dancesonic.player.analysis

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import org.dancesonic.player.subsonic.SubsonicException
import org.dancesonic.player.testsupport.TestSubsonicServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AnalysisClientTest {
    private lateinit var server: TestSubsonicServer
    private lateinit var client: AnalysisClient

    @Before
    fun setUp() {
        server = TestSubsonicServer()
        client = AnalysisClient(server.settings, server.http)
    }

    @After
    fun tearDown() {
        server.close()
    }

    @Test
    fun `a stored waveform is returned with its peaks`() =
        runBlocking {
            server.server.enqueue(MockResponse().setBody(waveformBody("[0,10,255]")))

            val answer = client.fetch("song-1")

            assertEquals(listOf(0, 10, 255), answer.peaks)
            assertNull(answer.retryAfterSeconds)
        }

    @Test
    fun `the key is sent in the header and never in the URL`() =
        runBlocking {
            server.server.enqueue(MockResponse().setBody(waveformBody("[1]")))

            client.fetch("song-1")

            val request = server.server.takeRequest()
            assertEquals("/api/analysis?id=song-1", request.path)
            assertEquals("test-api-key", request.getHeader("X-Api-Key"))
            assertNull(request.requestUrl?.queryParameter("apiKey"))
            assertNull(request.getHeader("Prefer"))
        }

    @Test
    fun `a pending answer keeps the waveform it already has`() =
        runBlocking {
            server.server.enqueue(
                MockResponse().setResponseCode(202).setHeader("Retry-After", "45")
                    .setBody(waveformBody("[5,6]")),
            )

            val answer = client.fetch("song-1")

            assertEquals(listOf(5, 6), answer.peaks)
        }

    @Test
    fun `a pending answer without a waveform asks to wait for the server's time`() =
        runBlocking {
            server.server.enqueue(MockResponse().setResponseCode(202).setHeader("Retry-After", "45").setBody("{}"))

            val answer = client.fetch("song-1")

            assertNull(answer.peaks)
            assertEquals(45, answer.retryAfterSeconds)
        }

    @Test
    fun `a pending answer without a Retry-After header waits the default time`() =
        runBlocking {
            server.server.enqueue(MockResponse().setResponseCode(202).setBody("{}"))

            assertEquals(30, client.fetch("song-1").retryAfterSeconds)
        }

    @Test
    fun `an unknown song gives nothing and no wait`() =
        runBlocking {
            server.server.enqueue(MockResponse().setResponseCode(404).setBody("{}"))

            val answer = client.fetch("missing")

            assertNull(answer.peaks)
            assertNull(answer.retryAfterSeconds)
        }

    @Test
    fun `a song with no waveform yet and no pending work gives nothing`() =
        runBlocking {
            server.server.enqueue(MockResponse().setBody("""{"id":"song-1","waveform":null}"""))

            val answer = client.fetch("song-1")

            assertNull(answer.peaks)
            assertNull(answer.retryAfterSeconds)
        }

    @Test
    fun `peaks outside the byte range are clamped`() =
        runBlocking {
            server.server.enqueue(MockResponse().setBody(waveformBody("[-3,300]")))

            assertEquals(listOf(0, 255), client.fetch("song-1").peaks)
        }

    @Test
    fun `a server error is reported with its status`() {
        server.server.enqueue(MockResponse().setResponseCode(401).setBody("{}"))

        val error =
            assertThrows(SubsonicException::class.java) {
                runBlocking { client.fetch("song-1") }
            }

        assertEquals(401, error.code)
    }

    private fun waveformBody(peaks: String): String {
        val bins = peaks.count { it == ',' } + 1
        return """{"id":"song-1","fileVersion":"sha256:x","waveform":{"bins":$bins,"peaks":$peaks}}"""
    }
}
