package org.dancesonic.player.playback.stretch

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.dancesonic.player.analysis.Onsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OnsetCacheTest {
    private val stored = mutableMapOf<String, Onsets>()
    private val loads = mutableListOf<String>()

    private fun onsets(vararg times: Long) = checkNotNull(Onsets.of(times.toList(), times.map { 200 }))

    @Test
    fun `prefetched songs are read without waiting, others are not held`() =
        runTest {
            val a = onsets(10, 20)
            stored["a"] = a
            stored["b"] = onsets(30)
            val cache = OnsetCache(this) { id -> stored[id].also { loads += id } }

            cache.prefetch(setOf("a"))
            assertNull(cache.onsetsFor("a"))
            advanceUntilIdle()

            assertSame(a, cache.onsetsFor("a"))
            assertNull(cache.onsetsFor("b"))
        }

    @Test
    fun `songs that leave the queue are dropped, and loaded ones are not loaded again`() =
        runTest {
            stored["a"] = onsets(10)
            stored["b"] = onsets(20)
            val cache = OnsetCache(this) { id -> stored[id].also { loads += id } }

            cache.prefetch(setOf("a", "b"))
            advanceUntilIdle()
            cache.prefetch(setOf("b", "c"))
            advanceUntilIdle()

            assertNull(cache.onsetsFor("a"))
            assertEquals(1, loads.count { it == "b" })
            assertNull(cache.onsetsFor("c"))
        }

    @Test
    fun `refresh picks up onsets stored after the first try`() =
        runTest {
            val cache = OnsetCache(this) { id -> stored[id] }
            cache.prefetch(setOf("a"))
            advanceUntilIdle()
            assertNull(cache.onsetsFor("a"))

            val a = onsets(5)
            stored["a"] = a
            cache.refresh("a")
            advanceUntilIdle()

            assertSame(a, cache.onsetsFor("a"))
        }
}
