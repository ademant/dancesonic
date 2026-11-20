package org.dancesonic.player.studio

import org.junit.Assert.assertEquals
import org.junit.Test

class StudioPlaybackTest {
    private fun state(
        playing: Boolean,
        positionMs: Long = 1_000,
        lengthMs: Long? = 200_000,
        rate: Double = 1.0,
    ) = StudioPlayState(
        playing = playing,
        song = "s1",
        index = 0,
        positionMs = positionMs,
        lengthMs = lengthMs,
        rate = rate,
        bpm = null,
        queue = listOf("s1"),
        danceTagId = null,
        danceName = null,
    )

    @Test
    fun `the speed sent to Studio is kept within the range Studio accepts`() {
        assertEquals(0.5, StudioPlayback.rateFor(0.1f), 0.0001)
        assertEquals(1.5, StudioPlayback.rateFor(3f), 0.0001)
        assertEquals(1.25, StudioPlayback.rateFor(1.25f), 0.0001)
    }

    @Test
    fun `a paused Studio reports the position it sent, without moving on`() {
        assertEquals(1_000L, StudioPlayback.positionAt(state(playing = false), reportedAtMs = 0, nowMs = 5_000))
    }

    @Test
    fun `a playing Studio moves on from its last report at the reported rate`() {
        assertEquals(
            3_000L,
            StudioPlayback.positionAt(state(playing = true, rate = 2.0), reportedAtMs = 0, nowMs = 1_000),
        )
    }

    @Test
    fun `the position never passes the end of the song`() {
        assertEquals(
            200_000L,
            StudioPlayback.positionAt(state(playing = true, positionMs = 199_000), reportedAtMs = 0, nowMs = 60_000),
        )
    }

    @Test
    fun `a report from the future does not move the position backwards`() {
        assertEquals(1_000L, StudioPlayback.positionAt(state(playing = true), reportedAtMs = 5_000, nowMs = 0))
    }

    @Test
    fun `a song list is cut to what one command may carry`() {
        val songs = (1..1_500).map { "s$it" }

        assertEquals(1_000, StudioPlayback.songsForCommand(songs).size)
        assertEquals("s1", StudioPlayback.songsForCommand(songs).first())
    }
}
