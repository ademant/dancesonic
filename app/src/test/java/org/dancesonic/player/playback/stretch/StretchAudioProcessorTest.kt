package org.dancesonic.player.playback.stretch

import androidx.media3.common.C
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.AudioProcessor.StreamMetadata
import androidx.media3.common.audio.AudioProcessor.UnhandledAudioFormatException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

class StretchAudioProcessorTest {
    private val stereo = AudioFormat(44_100, 2, C.ENCODING_PCM_16BIT)
    private val created = mutableListOf<FakeStretcher>()
    private val planned = mutableListOf<Float>()
    private val plan = StretchPlan(4_000, 1_000, longArrayOf(0, 10), doubleArrayOf(0.0, 10.0))
    private val processor =
        StretchAudioProcessor(
            createStretcher = { format -> FakeStretcher(format).also { created += it } },
            planFor = { speed, _ ->
                planned += speed
                plan
            },
        )

    @Test
    fun `rejects anything but 16-bit PCM`() {
        try {
            processor.configure(AudioFormat(44_100, 2, C.ENCODING_PCM_FLOAT))
            fail("Float PCM was accepted")
        } catch (expected: UnhandledAudioFormatException) {
            assertEquals(C.ENCODING_PCM_FLOAT, expected.inputAudioFormat.encoding)
        }
    }

    @Test
    fun `passes the format through`() {
        assertEquals(stereo, processor.configure(stereo))
    }

    @Test
    fun `inactive at 1x so audio is untouched`() {
        processor.configure(stereo)
        assertFalse(processor.isActive())
        processor.setSpeed(1.00005f)
        assertFalse(processor.isActive())
        processor.flush(StreamMetadata.DEFAULT)
        assertTrue(created.isEmpty())
    }

    @Test
    fun `active when slowed down or pitched`() {
        processor.configure(stereo)
        processor.setSpeed(0.8f)
        assertTrue(processor.isActive())
        processor.setSpeed(1f)
        processor.setPitch(1.1f)
        assertTrue(processor.isActive())
    }

    @Test
    fun `flush starts the stretcher at the new speed and reuses it for the same format`() {
        processor.configure(stereo)
        processor.setSpeed(0.8f)
        processor.flush(StreamMetadata.DEFAULT)
        processor.setSpeed(0.6f)
        processor.flush(StreamMetadata.DEFAULT)

        assertEquals(1, created.size)
        assertEquals(listOf(0.8f, 0.6f), created.single().starts)
    }

    @Test
    fun `a new format replaces and closes the stretcher`() {
        processor.configure(stereo)
        processor.setSpeed(0.8f)
        processor.flush(StreamMetadata.DEFAULT)
        processor.configure(AudioFormat(48_000, 2, C.ENCODING_PCM_16BIT))
        processor.flush(StreamMetadata.DEFAULT)

        assertEquals(2, created.size)
        assertTrue(created[0].closed)
        assertEquals(48_000, created[1].format.sampleRate)
    }

    @Test
    fun `stretches the input and ends after the output is drained`() {
        processor.configure(stereo)
        processor.setSpeed(0.5f)
        processor.flush(StreamMetadata.DEFAULT)

        val input = pcm(frames = 100)
        processor.queueInput(input)
        assertFalse(input.hasRemaining())
        processor.queueEndOfStream()
        assertFalse(processor.isEnded())

        assertEquals(200 * stereo.bytesPerFrame, processor.getOutput().remaining())
        assertTrue(processor.isEnded())
        assertFalse(processor.getOutput().hasRemaining())
    }

    @Test
    fun `media duration follows the speed of the stream now playing`() {
        processor.configure(stereo)
        processor.setSpeed(0.5f)
        processor.flush(StreamMetadata.DEFAULT)
        assertEquals(500_000L, processor.getMediaDuration(1_000_000L))

        // A new speed waits for the next flush.
        processor.setSpeed(0.8f)
        assertEquals(500_000L, processor.getMediaDuration(1_000_000L))
        processor.flush(StreamMetadata.DEFAULT)
        assertEquals(800_000L, processor.getMediaDuration(1_000_000L))
        assertEquals(1_250_000L, processor.getDurationAfterProcessorApplied(1_000_000L))
    }

    @Test
    fun `each flush asks for the plan of the stream that starts and hands it to the stretcher`() {
        processor.configure(stereo)
        processor.setSpeed(0.7f)
        processor.flush(StreamMetadata.DEFAULT)
        processor.setSpeed(0.5f)
        processor.flush(StreamMetadata.DEFAULT)

        assertEquals(listOf(0.7f, 0.5f), planned)
        assertSame(plan, created.single().plans.last())
    }

    @Test
    fun `no plan is asked for at 1x`() {
        processor.configure(stereo)
        processor.flush(StreamMetadata.DEFAULT)

        assertTrue(planned.isEmpty())
    }

    @Test
    fun `reset frees the stretcher and returns to 1x`() {
        processor.configure(stereo)
        processor.setSpeed(0.8f)
        processor.flush(StreamMetadata.DEFAULT)
        processor.reset()

        assertTrue(created.single().closed)
        assertFalse(processor.isActive())
    }

    @Test
    fun `chain hands speed and pitch to the processor`() {
        val chain = StretchAudioProcessorChain(processor)
        processor.configure(stereo)
        val params = PlaybackParameters(0.75f, 1f)

        assertSame(params, chain.applyPlaybackParameters(params))
        assertTrue(processor.isActive())
        processor.flush(StreamMetadata.DEFAULT)
        assertEquals(750_000L, chain.getMediaDuration(1_000_000L))
        assertEquals(processor, chain.getAudioProcessors().last())
    }

    @Test
    fun `chain leaves the duration alone at 1x`() {
        val chain = StretchAudioProcessorChain(processor)
        processor.configure(stereo)
        chain.applyPlaybackParameters(PlaybackParameters.DEFAULT)

        assertEquals(1_000_000L, chain.getMediaDuration(1_000_000L))
    }

    private fun pcm(frames: Int): ByteBuffer {
        val buffer = ByteBuffer.allocateDirect(frames * stereo.bytesPerFrame).order(ByteOrder.nativeOrder())
        repeat(frames * stereo.channelCount) { buffer.putShort(it.toShort()) }
        buffer.flip()
        return buffer
    }

    /** Repeats or drops whole frames: enough to check the processor's plumbing. */
    private class FakeStretcher(
        val format: AudioFormat,
    ) : TimeStretcher {
        val starts = mutableListOf<Float>()
        val plans = mutableListOf<StretchPlan>()
        var closed = false
        private var speed = 1f
        private var inputFrames = 0
        private var ended = false
        private var outputFrames = 0
        private var readFrames = 0

        override fun start(
            speed: Float,
            pitch: Float,
            plan: StretchPlan,
        ) {
            starts += speed
            plans += plan
            this.speed = speed
            inputFrames = 0
            outputFrames = 0
            readFrames = 0
            ended = false
        }

        override fun queueInput(input: ByteBuffer) {
            inputFrames += input.remaining() / format.bytesPerFrame
            input.position(input.limit())
        }

        override fun queueEndOfStream() {
            ended = true
            outputFrames = (inputFrames / speed).roundToInt()
        }

        override val availableFrames: Int
            get() = outputFrames - readFrames

        override fun readOutput(output: ByteBuffer) {
            val frames = minOf(availableFrames, output.remaining() / format.bytesPerFrame)
            repeat(frames * format.bytesPerFrame) { output.put(0) }
            readFrames += frames
        }

        override val isEnded: Boolean
            get() = ended && availableFrames == 0

        override fun close() {
            closed = true
        }
    }
}
