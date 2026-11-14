package org.dancesonic.player.playback.stretch

import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import java.nio.ByteBuffer

/**
 * Passes everything to [sink], and reports the calls [StreamTracker] needs to know which song and position the audio
 * processors are working on. The tracker is updated before the call is passed on, because the sink flushes its
 * processors inside [handleBuffer].
 */
@UnstableApi
class TrackingAudioSink(
    sink: AudioSink,
    private val tracker: StreamTracker,
) : ForwardingAudioSink(sink) {
    override fun handleBuffer(
        buffer: ByteBuffer,
        presentationTimeUs: Long,
        encodedAccessUnitCount: Int,
    ): Boolean {
        tracker.onBuffer(presentationTimeUs)
        return super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
    }

    override fun setOutputStreamOffsetUs(outputStreamOffsetUs: Long) {
        tracker.onStreamOffset(outputStreamOffsetUs)
        super.setOutputStreamOffsetUs(outputStreamOffsetUs)
    }

    override fun flush() {
        tracker.onFlush()
        super.flush()
    }

    override fun reset() {
        tracker.onReset()
        super.reset()
    }
}
