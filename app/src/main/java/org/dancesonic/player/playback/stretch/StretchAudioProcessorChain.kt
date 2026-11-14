package org.dancesonic.player.playback.stretch

import androidx.media3.common.PlaybackParameters
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessorChain
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.SilenceSkippingAudioProcessor

/**
 * Media3's default processor chain with [StretchAudioProcessor] in place of Sonic: silence skipping first, then the
 * time-stretch. Pass it to `DefaultAudioSink.Builder.setAudioProcessorChain`.
 */
@UnstableApi
class StretchAudioProcessorChain(
    private val stretch: StretchAudioProcessor = StretchAudioProcessor(),
    private val silenceSkipping: SilenceSkippingAudioProcessor = SilenceSkippingAudioProcessor(),
) : AudioProcessorChain {
    private val processors = arrayOf<AudioProcessor>(silenceSkipping, stretch)

    override fun getAudioProcessors(): Array<AudioProcessor> = processors

    override fun applyPlaybackParameters(playbackParameters: PlaybackParameters): PlaybackParameters {
        stretch.setSpeed(playbackParameters.speed)
        stretch.setPitch(playbackParameters.pitch)
        return playbackParameters
    }

    override fun applySkipSilenceEnabled(skipSilenceEnabled: Boolean): Boolean {
        silenceSkipping.setEnabled(skipSilenceEnabled)
        return skipSilenceEnabled
    }

    override fun getMediaDuration(playoutDuration: Long): Long =
        if (stretch.isActive()) stretch.getMediaDuration(playoutDuration) else playoutDuration

    override fun getSkippedOutputFrameCount(): Long = silenceSkipping.skippedFrames
}
