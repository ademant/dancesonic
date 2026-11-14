package org.dancesonic.player.dance

import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.mockwebserver.MockResponse
import org.dancesonic.player.testsupport.TestSubsonicServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The meter and sub-meter a lookup reads from a recording's tags. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DanceBrainzLookupMeterTest {
    private val mbid = "7f9c2a1e-0d3b-4c5a-9e8f-1a2b3c4d5e6f"
    private lateinit var server: TestSubsonicServer

    @Before
    fun setUp() {
        server = TestSubsonicServer()
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun lookupOf(tagsJson: String): Map<String, RecordingDance> {
        val body = """{"recordings":[{"mbid":"$mbid","tags":[$tagsJson]}]}"""
        server.server.enqueue(MockResponse().setResponseCode(200).setBody(body))
        val client = DanceBrainzClient(server.server.url("/").toString().toHttpUrl(), server.plain)
        return runBlocking { client.lookup(listOf(mbid)) }
    }

    @Test
    fun `a sub-meter reads its meter from its path and the sub-meter as its own name`() {
        val answer =
            lookupOf(
                """{"type":"meter","name":"3+3+2","path":[{"id":10,"name":"8/4"}]}""",
            ).getValue(mbid)

        assertEquals("8/4", answer.meter)
        assertEquals("3+3+2", answer.subMeter)
    }

    @Test
    fun `a meter without a sub-meter has no sub-meter`() {
        val answer = lookupOf("""{"type":"meter","name":"8/4","path":[]}""").getValue(mbid)

        assertEquals("8/4", answer.meter)
        assertNull(answer.subMeter)
    }

    @Test
    fun `a recording without a meter tag has neither`() {
        val answer = lookupOf("""{"type":"dance","name":"gavotte","path":[]}""").getValue(mbid)

        assertNull(answer.meter)
        assertNull(answer.subMeter)
    }
}
