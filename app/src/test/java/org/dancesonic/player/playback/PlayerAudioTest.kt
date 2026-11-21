package org.dancesonic.player.playback

import androidx.media3.common.C
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The player declares music for media, so the system can apply audio focus to it (#93). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PlayerAudioTest {
    @Test
    fun `the player plays music for media`() {
        assertEquals(C.USAGE_MEDIA, MUSIC_AUDIO.usage)
        assertEquals(C.AUDIO_CONTENT_TYPE_MUSIC, MUSIC_AUDIO.contentType)
    }

    @Test
    fun `a player built with the music rules uses those attributes`() {
        val player = ExoPlayer.Builder(ApplicationProvider.getApplicationContext()).withMusicAudio().build()
        try {
            assertEquals(MUSIC_AUDIO, player.audioAttributes)
        } finally {
            player.release()
        }
    }
}
