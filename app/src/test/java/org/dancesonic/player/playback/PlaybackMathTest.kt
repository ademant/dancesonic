package org.dancesonic.player.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackMathTest {
    private val multiplier = PlaybackSettings(multiplier = 1.2f)
    private val bpm = PlaybackSettings(tempoMode = TempoMode.Bpm, targetBpm = 120, multiplier = 0.9f)

    @Test
    fun `multiplier mode plays every track at the multiplier, whatever its tempo`() {
        assertEquals(1.2f, PlaybackMath.speed(multiplier, trackBpm = 90), 0f)
        assertEquals(1.2f, PlaybackMath.speed(multiplier, trackBpm = null), 0f)
    }

    @Test
    fun `bpm mode slows or speeds a track with a known tempo to the target`() {
        assertEquals(1f, PlaybackMath.speed(bpm, trackBpm = 120), 1e-6f)
        assertEquals(1.2f, PlaybackMath.speed(bpm, trackBpm = 100), 1e-6f)
        assertEquals(0.8f, PlaybackMath.speed(bpm, trackBpm = 150), 1e-6f)
    }

    @Test
    fun `bpm mode uses the multiplier for a track without a tempo`() {
        assertEquals(0.9f, PlaybackMath.speed(bpm, trackBpm = null), 0f)
        assertEquals(0.9f, PlaybackMath.speed(bpm, trackBpm = 0), 0f)
    }

    @Test
    fun `the speed is kept within the range the player bar offers`() {
        assertEquals(PlaybackMath.MAX_TEMPO, PlaybackMath.speed(bpm, trackBpm = 60), 0f)
        assertEquals(PlaybackMath.MIN_TEMPO, PlaybackMath.speed(bpm, trackBpm = 400), 0f)
        assertEquals(PlaybackMath.MAX_TEMPO, PlaybackMath.speed(PlaybackSettings(multiplier = 3f), null), 0f)
    }

    @Test
    fun `without replay gain the volume stays at full`() {
        assertEquals(1f, PlaybackMath.volume(replayGainEnabled = false, trackGainDb = -6.0, trackPeak = 0.5), 0f)
    }

    @Test
    fun `a track without a gain value is not changed`() {
        assertEquals(1f, PlaybackMath.volume(replayGainEnabled = true, trackGainDb = null, trackPeak = null), 0f)
    }

    @Test
    fun `a loud track is turned down by its gain`() {
        assertEquals(
            0.5f,
            PlaybackMath.volume(replayGainEnabled = true, trackGainDb = -6.0206, trackPeak = null),
            1e-4f,
        )
    }

    @Test
    fun `a quiet track is never turned up`() {
        assertEquals(1f, PlaybackMath.volume(replayGainEnabled = true, trackGainDb = 4.0, trackPeak = null), 0f)
    }

    @Test
    fun `the volume stops before the peak would clip`() {
        // Gain would allow 1.5, but the peak 0.8 allows at most 1.25 and the player caps at 1.
        assertEquals(1f, PlaybackMath.volume(replayGainEnabled = true, trackGainDb = 3.5, trackPeak = 0.8), 0f)
        // A loud peak limits a track with a gain that would otherwise leave it at 0.9.
        assertEquals(0.5f, PlaybackMath.volume(replayGainEnabled = true, trackGainDb = -1.0, trackPeak = 2.0), 1e-4f)
    }
}
