package org.dancesonic.player.playback.stretch

import java.nio.ByteBuffer

/**
 * Pitch-preserving time-stretch of one stream of interleaved, native-order 16-bit PCM.
 *
 * The output follows the [StretchPlan]'s time map: uniformly, output frame k is the input at time k * speed, so a
 * stream of N input frames gives round(N / speed) output frames once [queueEndOfStream] was called. A plan with
 * onsets moves frames between its anchors but keeps them on that timeline.
 */
interface TimeStretcher : AutoCloseable {
    /** Starts a new stream at [speed] and [pitch], stretched as [plan] says, dropping anything buffered. */
    fun start(
        speed: Float,
        pitch: Float,
        plan: StretchPlan = StretchPlan.UNIFORM,
    )

    /** Consumes all whole frames between [input]'s position and limit, and advances its position. */
    fun queueInput(input: ByteBuffer)

    /** Marks the end of the stream, so the remaining output becomes available. */
    fun queueEndOfStream()

    /** Output frames ready to read. */
    val availableFrames: Int

    /** Writes as many output frames as fit between [output]'s position and limit, and advances its position. */
    fun readOutput(output: ByteBuffer)

    /** True once the end of stream was queued and all output was read. */
    val isEnded: Boolean

    /** Frees the stretcher. It must not be used afterwards. */
    override fun close()
}
