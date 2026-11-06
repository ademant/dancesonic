package org.dancesonic.player.playback

import org.junit.Assert.assertEquals
import org.junit.Test

/** One cached span in the test: its song and its size. Oldest first in the lists below. */
private data class Span(
    val songId: String,
    val length: Long,
)

class KeepAwareEvictorTest {
    private val oldestFirst =
        listOf(Span("k1", 30), Span("k2", 30), Span("k3", 30))

    private fun victimsOf(
        total: Long,
        max: Long,
        kept: Set<String> = emptySet(),
    ): List<String> =
        chooseVictims(
            oldestFirst = oldestFirst,
            songId = Span::songId,
            length = Span::length,
            bytesToFree = total - max,
            isKept = { it in kept },
        ).map { it.songId }

    @Test
    fun `nothing is removed while the cache fits`() {
        assertEquals(emptyList<String>(), victimsOf(total = 90, max = 90))
        assertEquals(emptyList<String>(), victimsOf(total = 50, max = 90))
    }

    @Test
    fun `the oldest spans go first until the cache fits`() {
        assertEquals(listOf("k1", "k2"), victimsOf(total = 90, max = 30))
    }

    @Test
    fun `a kept song is skipped and the next oldest goes instead`() {
        assertEquals(listOf("k2", "k3"), victimsOf(total = 90, max = 30, kept = setOf("k1")))
    }

    @Test
    fun `kept songs alone may keep the cache over the cap`() {
        assertEquals(emptyList<String>(), victimsOf(total = 90, max = 10, kept = setOf("k1", "k2", "k3")))
    }
}
