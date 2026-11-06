package org.dancesonic.player.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackProgressTest {
    /** Media3 reports an unknown length as its TIME_UNSET value, which is negative. */
    private val unknownLength = Long.MIN_VALUE + 1

    @Test
    fun `a position inside the track is kept as it is`() {
        assertEquals(
            PlaybackProgress(positionMs = 10_000L, durationMs = 40_000L),
            PlaybackProgress.of(10_000L, 40_000L),
        )
    }

    @Test
    fun `an unknown length reads as zero, and so does the position`() {
        assertEquals(PlaybackProgress(0L, 0L), PlaybackProgress.of(5_000L, unknownLength))
    }

    @Test
    fun `a position past the end stops at the end`() {
        assertEquals(PlaybackProgress(40_000L, 40_000L), PlaybackProgress.of(41_000L, 40_000L))
    }

    @Test
    fun `a negative position is clamped to the start`() {
        assertEquals(PlaybackProgress(0L, 40_000L), PlaybackProgress.of(-1L, 40_000L))
    }
}
