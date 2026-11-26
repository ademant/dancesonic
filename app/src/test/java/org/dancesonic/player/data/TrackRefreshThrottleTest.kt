package org.dancesonic.player.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/** One-track refreshes are throttled per track and coalesced (#121). */
class TrackRefreshThrottleTest {
    private var now = 0L
    private val throttle = TrackRefreshThrottle(clock = { now }, cooldownMillis = COOLDOWN)
    private var requests = 0

    private suspend fun refresh(songId: String) = throttle.run(songId) { requests++ }

    @Test
    fun `a track is asked for once per cooldown`() =
        runTest {
            assertTrue(refresh("s-1"))
            now += COOLDOWN - 1
            assertFalse(refresh("s-1"))
            assertEquals(1, requests)

            now += 1
            assertTrue(refresh("s-1"))
            assertEquals(2, requests)
        }

    @Test
    fun `other tracks are not held back`() =
        runTest {
            refresh("s-1")
            refresh("s-2")

            assertEquals(2, requests)
        }

    @Test
    fun `a refresh asked while one runs joins it`() =
        runTest {
            val release = CompletableDeferred<Unit>()
            val first =
                async {
                    throttle.run("s-1") {
                        requests++
                        release.await()
                    }
                }
            yield()
            val second = async { refresh("s-1") }
            yield()
            release.complete(Unit)

            assertTrue(first.await())
            assertFalse(second.await())
            assertEquals(1, requests)
        }

    @Test
    fun `a failed refresh also waits for the cooldown`() =
        runTest {
            assertThrows(IOException::class.java) {
                kotlinx.coroutines.runBlocking { throttle.run("s-1") { throw IOException("offline") } }
            }

            assertFalse(refresh("s-1"))
            assertEquals(0, requests)
        }

    private companion object {
        const val COOLDOWN = 1_000L
    }
}
