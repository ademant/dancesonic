package org.dancesonic.player.playback.stretch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StreamTrackerTest {
    private val tracker = StreamTracker()

    @Test
    fun `nothing is known before the first buffer`() {
        tracker.setQueue("a", "b")
        tracker.onStreamOffset(OFFSET)

        assertNull(tracker.position())
    }

    @Test
    fun `the first stream after a flush is the current song, at its media time`() {
        tracker.setQueue("a", "b")
        tracker.onFlush()
        tracker.onStreamOffset(OFFSET)
        tracker.onBuffer(OFFSET + 2_500_000)

        assertEquals(StreamPosition("a", 2_500_000), tracker.position())
    }

    @Test
    fun `a stream that follows without a flush is the next song`() {
        tracker.setQueue("a", "b")
        tracker.onStreamOffset(OFFSET)
        tracker.onBuffer(OFFSET)
        tracker.onBuffer(OFFSET + 200_000_000)

        tracker.onStreamOffset(OFFSET + 210_000_000)
        tracker.onBuffer(OFFSET + 210_000_000)

        assertEquals(StreamPosition("b", 0), tracker.position())
    }

    @Test
    fun `a seek within the song keeps the song and moves the position`() {
        tracker.setQueue("a", "b")
        tracker.onStreamOffset(OFFSET)
        tracker.onBuffer(OFFSET)

        tracker.onFlush()
        tracker.onBuffer(OFFSET + 90_000_000)

        assertEquals(StreamPosition("a", 90_000_000), tracker.position())
    }

    @Test
    fun `a jump to another item after a flush takes the new current song`() {
        tracker.setQueue("a", "b")
        tracker.onStreamOffset(OFFSET)
        tracker.onBuffer(OFFSET)

        tracker.setQueue("d", "e")
        tracker.onFlush()
        tracker.onStreamOffset(OFFSET + 500_000_000)
        tracker.onBuffer(OFFSET + 500_000_000)

        assertEquals(StreamPosition("d", 0), tracker.position())
    }

    @Test
    fun `the same offset reported again does not move on to the next song`() {
        tracker.setQueue("a", "b")
        tracker.onStreamOffset(OFFSET)
        tracker.onBuffer(OFFSET)
        tracker.onStreamOffset(OFFSET)
        tracker.onBuffer(OFFSET + 1_000)

        assertEquals("a", tracker.position()?.songId)
    }

    @Test
    fun `a position before the stream start is clamped to zero`() {
        tracker.setQueue("a", null)
        tracker.onStreamOffset(OFFSET)
        tracker.onBuffer(OFFSET - 500)

        assertEquals(0L, tracker.position()?.mediaTimeUs)
    }

    @Test
    fun `after a reset the song is unknown until the queue says`() {
        tracker.setQueue("a", "b")
        tracker.onStreamOffset(OFFSET)
        tracker.onBuffer(OFFSET)
        tracker.onReset()
        assertNull(tracker.position())

        tracker.setQueue("c", null)
        tracker.onBuffer(1_000)
        assertEquals(StreamPosition("c", 1_000), tracker.position())
    }

    private companion object {
        /** Media3's first renderer offset is large; any value works. */
        const val OFFSET = 1_000_000_000_000L
    }
}
