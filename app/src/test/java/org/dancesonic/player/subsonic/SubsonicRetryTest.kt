package org.dancesonic.player.subsonic

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.SocketPolicy
import org.dancesonic.player.testsupport.TestSubsonicServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test

class SubsonicRetryTest {
    private lateinit var fake: TestSubsonicServer
    private lateinit var client: SubsonicClient

    @Before
    fun setUp() {
        fake = TestSubsonicServer()
        // The backoff is shortened so the tests stay fast; the schedule itself is the same.
        client = SubsonicClient(fake.settings, SubsonicHttp.service(fake.settings.baseUrl, fake.http), 1L)
    }

    @After
    fun tearDown() {
        fake.close()
    }

    @Test
    fun `a transient server error is retried until the server answers`() =
        runBlocking {
            fake.server.enqueue(MockResponse().setResponseCode(503))
            fake.server.enqueue(MockResponse().setResponseCode(500))
            fake.enqueueJson(NO_ARTISTS)

            assertEquals(emptyList<ArtistDto>(), client.artists())
            assertEquals(3, fake.server.requestCount)
        }

    @Test
    fun `too many requests is treated as transient`() =
        runBlocking {
            fake.server.enqueue(MockResponse().setResponseCode(429))
            fake.enqueueJson(NO_ARTISTS)

            assertEquals(emptyList<ArtistDto>(), client.artists())
            assertEquals(2, fake.server.requestCount)
        }

    @Test
    fun `a dropped connection is retried`() =
        runBlocking {
            fake.server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
            fake.enqueueJson(NO_ARTISTS)

            assertEquals(emptyList<ArtistDto>(), client.artists())
            assertEquals(2, fake.server.requestCount)
        }

    @Test
    fun `retrying gives up after the last attempt`() {
        repeat(SubsonicClient.MAX_ATTEMPTS) { fake.server.enqueue(MockResponse().setResponseCode(503)) }

        assertThrows(SubsonicException::class.java) { runBlocking { client.artists() } }
        assertEquals(SubsonicClient.MAX_ATTEMPTS, fake.server.requestCount)
    }

    @Test
    fun `a client error is not retried`() {
        fake.enqueueError(404)

        assertThrows(SubsonicException::class.java) { runBlocking { client.artists() } }
        assertEquals(1, fake.server.requestCount)
    }

    @Test
    fun `a failed status from the server is not retried`() {
        fake.enqueueJson(FAILED_WRONG_KEY)

        val error = assertThrows(SubsonicException::class.java) { runBlocking { client.artists() } }
        assertEquals("Wrong username or password", error.message)
        assertEquals(1, fake.server.requestCount)
    }

    private companion object {
        const val NO_ARTISTS = """{"subsonic-response":{"status":"ok","artists":{"index":[]}}}"""
        const val FAILED_WRONG_KEY =
            """{"subsonic-response":{"status":"failed","error":{"code":40,"message":"Wrong username or password"}}}"""
    }
}
