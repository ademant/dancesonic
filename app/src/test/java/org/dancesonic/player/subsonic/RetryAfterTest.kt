package org.dancesonic.player.subsonic

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import org.dancesonic.player.testsupport.TestSubsonicServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test

/** A rate-limited server's `Retry-After` sets the wait before the next attempt (#123). */
class RetryAfterTest {
    private lateinit var fake: TestSubsonicServer
    private lateinit var client: SubsonicClient
    private val waits = mutableListOf<Long>()
    private val now = 1_764_000_000_000L

    @Before
    fun setUp() {
        fake = TestSubsonicServer()
        client =
            SubsonicClient(
                fake.settings,
                SubsonicHttp.service(fake.settings.baseUrl, fake.http),
                retryBackoffMillis = 500L,
                pause = { waits += it },
                clock = { now },
            )
    }

    @After
    fun tearDown() {
        fake.close()
    }

    @Test
    fun `a 429 with Retry-After seconds waits that long`() =
        runBlocking {
            fake.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "7"))
            fake.enqueueJson(SubsonicRetryTest.NO_ARTISTS)

            client.artists()

            assertEquals(listOf(7_000L), waits)
        }

    @Test
    fun `a Retry-After date waits until then`() =
        runBlocking {
            fake.enqueue(
                MockResponse().setResponseCode(429).setHeader("Retry-After", "Mon, 24 Nov 2025 16:00:05 GMT"),
            )
            fake.enqueueJson(SubsonicRetryTest.NO_ARTISTS)

            client.artists()

            assertEquals(listOf(retryAfterMillis("Mon, 24 Nov 2025 16:00:05 GMT", now)), waits)
        }

    @Test
    fun `a missing or invalid Retry-After falls back to the bounded backoff`() =
        runBlocking {
            fake.enqueue(MockResponse().setResponseCode(429))
            fake.enqueue(MockResponse().setResponseCode(503).setHeader("Retry-After", "soon"))
            fake.enqueueJson(SubsonicRetryTest.NO_ARTISTS)

            client.artists()

            assertEquals(listOf(500L, 1_000L), waits)
        }

    @Test
    fun `the attempt limit holds with Retry-After`() {
        repeat(SubsonicClient.MAX_ATTEMPTS) {
            fake.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "1"))
        }

        assertThrows(SubsonicException::class.java) { runBlocking { client.artists() } }
        assertEquals(SubsonicClient.MAX_ATTEMPTS, fake.server.requestCount)
        assertEquals(SubsonicClient.MAX_ATTEMPTS - 1, waits.size)
    }

    @Test
    fun `the header is parsed in both forms and capped`() {
        assertEquals(0L, retryAfterMillis("0", now))
        assertEquals(SubsonicClient.MAX_RETRY_AFTER_MILLIS, retryAfterMillis("86400", now))
        assertEquals(SubsonicClient.MAX_RETRY_AFTER_MILLIS, retryAfterMillis("99999999999999999999", now))
        assertEquals(0L, retryAfterMillis("Mon, 01 Jan 2001 00:00:00 GMT", now))
        assertNull(retryAfterMillis(null, now))
        assertNull(retryAfterMillis("", now))
        assertNull(retryAfterMillis("-5", now))
        assertNull(retryAfterMillis("in a while", now))
    }
}
