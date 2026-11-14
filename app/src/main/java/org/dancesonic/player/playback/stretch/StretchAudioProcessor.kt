package org.dancesonic.player.playback.stretch

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.AudioProcessor.StreamMetadata
import androidx.media3.common.audio.AudioProcessor.UnhandledAudioFormatException
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Changes speed and pitch with a [TimeStretcher], in place of Media3's `SonicAudioProcessor`. Takes and gives 16-bit
 * PCM, like Sonic. At 1x speed and pitch it is inactive, so the audio passes through untouched.
 *
 * New values from [setSpeed] and [setPitch] apply at the next flush; the audio sink drains and flushes the
 * processors when the playback parameters change, and at every seek and track start.
 *
 * At each flush [planFor] gives the [StretchPlan] for the stream that starts: with the song's onsets, attacks play at
 * close to their original speed (see [StretchPlanner]). The media position is still reported on the nominal timeline,
 * which the plan meets at every anchor.
 */
@UnstableApi
class StretchAudioProcessor(
    private val createStretcher: (AudioFormat) -> TimeStretcher = { format ->
        NativeTimeStretcher(format.channelCount, format.sampleRate)
    },
    private val planFor: (speed: Float, format: AudioFormat) -> StretchPlan = { _, _ -> StretchPlan.UNIFORM },
) : AudioProcessor {
    private var speed = 1f
    private var pitch = 1f

    /** The speed of the stream now flowing, fixed at the last flush. */
    private var streamSpeed = 1f
    private var pendingFormat = AudioFormat.NOT_SET
    private var format = AudioFormat.NOT_SET
    private var stretcher: TimeStretcher? = null
    private var buffer: ByteBuffer = AudioProcessor.EMPTY_BUFFER
    private var inputEnded = false

    /** Sets the speed for the next stream. Must be positive. */
    fun setSpeed(speed: Float) {
        require(speed > 0f) { "Speed must be positive" }
        this.speed = speed
    }

    /** Sets the pitch factor for the next stream. Must be positive. */
    fun setPitch(pitch: Float) {
        require(pitch > 0f) { "Pitch must be positive" }
        this.pitch = pitch
    }

    /**
     * The media duration played in [playoutDuration] of output. Exact, because output frame k is the input at
     * time k * speed.
     */
    fun getMediaDuration(playoutDuration: Long): Long = (playoutDuration * streamSpeed.toDouble()).roundToLong()

    override fun getDurationAfterProcessorApplied(durationUs: Long): Long =
        (durationUs / streamSpeed.toDouble()).roundToLong()

    override fun configure(inputAudioFormat: AudioFormat): AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            throw UnhandledAudioFormatException(inputAudioFormat)
        }
        pendingFormat = inputAudioFormat
        return inputAudioFormat
    }

    override fun isActive(): Boolean =
        pendingFormat.sampleRate != Format.NO_VALUE && !(isUnity(speed) && isUnity(pitch))

    override fun queueInput(inputBuffer: ByteBuffer) {
        val active = stretcher
        if (active == null) {
            inputBuffer.position(inputBuffer.limit())
        } else {
            active.queueInput(inputBuffer)
        }
    }

    override fun queueEndOfStream() {
        stretcher?.queueEndOfStream()
        inputEnded = true
    }

    override fun getOutput(): ByteBuffer {
        val active = stretcher ?: return AudioProcessor.EMPTY_BUFFER
        val bytes = active.availableFrames * format.bytesPerFrame
        if (bytes <= 0) return AudioProcessor.EMPTY_BUFFER
        if (buffer.capacity() < bytes) {
            buffer = ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder())
        } else {
            buffer.clear()
        }
        buffer.limit(bytes)
        active.readOutput(buffer)
        buffer.flip()
        return buffer
    }

    override fun isEnded(): Boolean = inputEnded && stretcher?.isEnded != false

    override fun flush(streamMetadata: StreamMetadata) {
        if (isActive()) {
            if (stretcher == null || pendingFormat != format) {
                stretcher?.close()
                stretcher = createStretcher(pendingFormat)
            }
            format = pendingFormat
            streamSpeed = speed
            stretcher?.start(speed, pitch, planFor(speed, format))
        } else {
            streamSpeed = 1f
        }
        inputEnded = false
    }

    override fun reset() {
        stretcher?.close()
        stretcher = null
        speed = 1f
        pitch = 1f
        streamSpeed = 1f
        pendingFormat = AudioFormat.NOT_SET
        format = AudioFormat.NOT_SET
        buffer = AudioProcessor.EMPTY_BUFFER
        inputEnded = false
    }

    private fun isUnity(value: Float) = abs(value - 1f) < UNITY_THRESHOLD

    private companion object {
        /** Same tolerance as Sonic: closer to 1 than this counts as 1. */
        const val UNITY_THRESHOLD = 0.0001f
    }
}
