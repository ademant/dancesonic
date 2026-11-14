package org.dancesonic.player.playback.stretch

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import org.dancesonic.player.analysis.Onsets

/**
 * The default renderers, with an audio sink that changes tempo through [StretchAudioProcessorChain] (Signalsmith
 * Stretch) instead of Sonic, which sounds bad on slowed-down music. [tracker] learns which song the sink is fed, and
 * [onsets] gives that song's onsets, so attacks stay sharp; a song without onsets is stretched uniformly.
 */
@UnstableApi
class StretchRenderersFactory(
    context: Context,
    private val tracker: StreamTracker,
    private val onsets: (String) -> Onsets?,
) : DefaultRenderersFactory(context) {
    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioOutputPlaybackParams: Boolean,
    ): AudioSink {
        val processor =
            StretchAudioProcessor(planFor = { speed, format ->
                val position = tracker.position()
                StretchPlanner.plan(
                    onsets = position?.songId?.let(onsets),
                    streamStartUs = position?.mediaTimeUs ?: 0L,
                    speed = speed,
                    sampleRate = format.sampleRate,
                )
            })
        val sink =
            DefaultAudioSink
                .Builder(context)
                .setEnableFloatOutput(enableFloatOutput)
                .setEnableAudioOutputPlaybackParameters(enableAudioOutputPlaybackParams)
                .setAudioProcessorChain(StretchAudioProcessorChain(processor))
                .build()
        return TrackingAudioSink(sink, tracker)
    }
}
