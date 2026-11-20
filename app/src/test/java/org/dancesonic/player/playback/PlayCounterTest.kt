package org.dancesonic.player.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayCounterTest {
    private var now = 0L
    private val counted = mutableListOf<Pair<String, Long>>()
    private val counter = PlayCounter(clock = { now }, onCounted = { id, start -> counted += id to start })

    /** Plays for [ms] of wall-clock time in ticks of [TICK_MS], starting from the current clock. */
    private fun playFor(
        ms: Long,
        speed: Float = 1f,
    ) {
        repeat((ms / TICK_MS).toInt()) {
            now += TICK_MS
            counter.tick(playing = true, speed = speed)
        }
    }

    private fun pauseFor(ms: Long) {
        repeat((ms / TICK_MS).toInt()) {
            now += TICK_MS
            counter.tick(playing = false, speed = 1f)
        }
    }

    @Test
    fun `a track counts at half its length when that is under four minutes`() {
        counter.trackStarted("short", durationMs = 120_000)
        counter.tick(playing = true, speed = 1f)

        playFor(59_000)
        assertTrue(counted.isEmpty())

        playFor(2_000)
        assertEquals(listOf("short" to 0L), counted)
    }

    @Test
    fun `a long track counts at four minutes, not half its length`() {
        counter.trackStarted("long", durationMs = 20 * 60_000)
        counter.tick(playing = true, speed = 1f)

        playFor(239_000)
        assertTrue(counted.isEmpty())

        playFor(2_000)
        assertEquals(1, counted.size)
    }

    @Test
    fun `a track left before it counts is not counted`() {
        counter.trackStarted("skipped", durationMs = 120_000)
        counter.tick(playing = true, speed = 1f)
        playFor(30_000)

        counter.trackStarted("next", durationMs = 120_000)

        assertTrue(counted.isEmpty())
    }

    @Test
    fun `time while paused does not count`() {
        counter.trackStarted("paused", durationMs = 120_000)
        counter.tick(playing = true, speed = 1f)
        playFor(50_000)
        pauseFor(10 * 60_000)

        playFor(5_000)
        assertTrue(counted.isEmpty())

        playFor(5_000)
        assertEquals(1, counted.size)
    }

    @Test
    fun `the count follows track time, so a faster tempo counts sooner`() {
        counter.trackStarted("fast", durationMs = 120_000)
        counter.tick(playing = true, speed = 1f)

        playFor(30_000, speed = 2f)

        assertEquals(1, counted.size)
    }

    @Test
    fun `a track of unknown length never counts`() {
        counter.trackStarted("unknown", durationMs = 0)
        counter.tick(playing = true, speed = 1f)

        playFor(60 * 60_000)

        assertTrue(counted.isEmpty())
    }

    @Test
    fun `a track counts once`() {
        counter.trackStarted("once", durationMs = 120_000)
        counter.tick(playing = true, speed = 1f)

        playFor(10 * 60_000)

        assertEquals(1, counted.size)
    }

    @Test
    fun `a long gap between ticks adds only the capped amount`() {
        counter.trackStarted("gap", durationMs = 120_000)
        counter.tick(playing = true, speed = 1f)

        now += 10 * 60_000
        counter.tick(playing = true, speed = 1f)

        assertTrue(counted.isEmpty())
    }

    @Test
    fun `the start time is when the track started`() {
        now = 1_000L
        counter.trackStarted("timed", durationMs = 120_000)
        counter.tick(playing = true, speed = 1f)

        playFor(60_000)

        assertEquals(listOf("timed" to 1_000L), counted)
    }

    private companion object {
        const val TICK_MS = 500L
    }
}
