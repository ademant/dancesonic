package org.dancesonic.player.playback.stretch

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * [TimeStretcher] backed by Signalsmith Stretch in the `dancestretch` native library. Signalsmith Stretch is a
 * spectral stretcher made for music; Media3's built-in Sonic is made for speech and warbles on music when slowed.
 */
class NativeTimeStretcher(
    channelCount: Int,
    sampleRate: Int,
) : TimeStretcher {
    private val bytesPerFrame = channelCount * Short.SIZE_BYTES
    private var handle: Long = nativeCreate(channelCount, sampleRate)
    private var scratch: ByteBuffer = ByteBuffer.allocateDirect(0)

    init {
        check(handle != 0L) { "Could not create the time stretcher" }
    }

    override fun start(
        speed: Float,
        pitch: Float,
    ) = nativeStart(live(), speed, pitch)

    override fun queueInput(input: ByteBuffer) {
        val frames = input.remaining() / bytesPerFrame
        if (frames == 0) return
        val bytes = frames * bytesPerFrame
        val queued =
            if (input.isDirect) {
                nativeQueueInput(live(), input, input.position(), frames)
            } else {
                // Decoder output is direct; a heap buffer only comes from unusual upstream processors.
                val direct = scratchOf(bytes)
                val slice = input.duplicate()
                slice.limit(input.position() + bytes)
                direct.put(slice)
                nativeQueueInput(live(), direct, 0, frames)
            }
        check(queued) { "Audio buffer has no native address" }
        input.position(input.position() + bytes)
    }

    override fun queueEndOfStream() = nativeQueueEndOfStream(live())

    override val availableFrames: Int
        get() = nativeAvailableFrames(live())

    override fun readOutput(output: ByteBuffer) {
        check(output.isDirect) { "Output buffer must be direct" }
        val frames = nativeReadOutput(live(), output, output.position(), output.remaining() / bytesPerFrame)
        check(frames >= 0) { "Audio buffer has no native address" }
        output.position(output.position() + frames * bytesPerFrame)
    }

    override val isEnded: Boolean
        get() = nativeIsEnded(live())

    override fun close() {
        if (handle != 0L) {
            nativeDestroy(handle)
            handle = 0L
        }
    }

    private fun live(): Long {
        check(handle != 0L) { "Time stretcher was closed" }
        return handle
    }

    private fun scratchOf(bytes: Int): ByteBuffer {
        if (scratch.capacity() < bytes) scratch = ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder())
        scratch.clear()
        return scratch
    }

    private external fun nativeCreate(
        channels: Int,
        sampleRate: Int,
    ): Long

    private external fun nativeDestroy(handle: Long)

    private external fun nativeStart(
        handle: Long,
        speed: Float,
        pitch: Float,
    )

    private external fun nativeQueueInput(
        handle: Long,
        buffer: ByteBuffer,
        byteOffset: Int,
        frames: Int,
    ): Boolean

    private external fun nativeQueueEndOfStream(handle: Long)

    private external fun nativeAvailableFrames(handle: Long): Int

    private external fun nativeReadOutput(
        handle: Long,
        buffer: ByteBuffer,
        byteOffset: Int,
        maxFrames: Int,
    ): Int

    private external fun nativeIsEnded(handle: Long): Boolean

    private companion object {
        init {
            System.loadLibrary("dancestretch")
        }
    }
}
