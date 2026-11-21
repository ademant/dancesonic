package org.dancesonic.player.playback

import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.exoplayer.ExoPlayer

/** What the player tells the system it plays: music, for media. */
internal val MUSIC_AUDIO: AudioAttributes =
    AudioAttributes.Builder()
        .setUsage(C.USAGE_MEDIA)
        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
        .build()

/**
 * The player's audio rules (#93). Media3 handles audio focus: it ducks for a navigation prompt, pauses for a call and
 * resumes after a transient loss, and stays paused after a permanent one, such as another music app starting. A
 * headphone or Bluetooth disconnect pauses playback.
 */
internal fun ExoPlayer.Builder.withMusicAudio(): ExoPlayer.Builder =
    setAudioAttributes(MUSIC_AUDIO, HANDLE_AUDIO_FOCUS)
        .setHandleAudioBecomingNoisy(true)

/** Named so the call above says what its `true` means; Java methods take no named arguments. */
private const val HANDLE_AUDIO_FOCUS = true
