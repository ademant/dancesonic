package org.dancesonic.player.analysis

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaDataSource
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import kotlinx.coroutines.ensureActive
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.coroutines.coroutineContext
import kotlin.math.abs

/**
 * Builds a waveform by decoding a track on the device with the platform codec. Used only when the server has no
 * analysis for the track. Only 16-bit PCM output is handled; any other format gives null.
 */
internal object LocalWaveform {
    private const val TIMEOUT_US = 10_000L
    private const val MILLIS_PER_SECOND = 1_000L
    private const val TAG = "LocalWaveform"

    /**
     * [bins] peaks spread over the track's [durationMs], or null when there is no audio track or it cannot be decoded.
     * The source must be readable without the network; see [CachedMediaDataSource].
     */
    suspend fun decode(
        source: MediaDataSource,
        durationMs: Long,
        bins: Int,
    ): List<Int>? {
        if (durationMs <= 0) return null
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(source)
            decodeTrack(extractor, durationMs, bins)
        } catch (e: IllegalStateException) {
            // The platform rejects a format it cannot decode; the track then has no local waveform.
            Log.d(TAG, "Local waveform skipped: ${e.javaClass.simpleName}")
            null
        } catch (e: IllegalArgumentException) {
            Log.d(TAG, "Local waveform skipped: ${e.javaClass.simpleName}")
            null
        } finally {
            extractor.release()
        }
    }

    private suspend fun decodeTrack(
        extractor: MediaExtractor,
        durationMs: Long,
        bins: Int,
    ): List<Int>? {
        val track = audioTrack(extractor) ?: return null
        extractor.selectTrack(track)
        val format = extractor.getTrackFormat(track)
        val mime = format.getString(MediaFormat.KEY_MIME) ?: return null
        val codec = MediaCodec.createDecoderByType(mime)
        return try {
            codec.configure(format, null, null, 0)
            codec.start()
            decodeAll(extractor, codec, PeakCollector(durationMs, bins))
        } finally {
            codec.release()
        }
    }

    private suspend fun decodeAll(
        extractor: MediaExtractor,
        codec: MediaCodec,
        collector: PeakCollector,
    ): List<Int>? {
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false
        while (!outputDone) {
            coroutineContext.ensureActive()
            if (!inputDone) inputDone = feedInput(extractor, codec)
            when (val index = codec.dequeueOutputBuffer(info, TIMEOUT_US)) {
                MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    if (!collector.formatChanged(codec.outputFormat)) return null
                }
                else ->
                    if (index >= 0) {
                        collector.consume(codec.getOutputBuffer(index), info)
                        codec.releaseOutputBuffer(index, false)
                        outputDone = (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                    }
            }
        }
        return collector.result()
    }

    /** Queues one compressed sample. Returns true once the end of the track has been queued. */
    private fun feedInput(
        extractor: MediaExtractor,
        codec: MediaCodec,
    ): Boolean {
        val index = codec.dequeueInputBuffer(TIMEOUT_US)
        if (index < 0) return false
        val buffer = codec.getInputBuffer(index) ?: return false
        val size = extractor.readSampleData(buffer, 0)
        return if (size < 0) {
            codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            true
        } else {
            codec.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
            extractor.advance()
            false
        }
    }

    private fun audioTrack(extractor: MediaExtractor): Int? =
        (0 until extractor.trackCount).firstOrNull {
            extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        }

    /** Turns decoded PCM into peaks. It learns the layout from the decoder's output format. */
    private class PeakCollector(
        private val durationMs: Long,
        private val bins: Int,
    ) {
        private var peaks: PeakBins? = null
        private var channels = 0
        private var frame = 0L

        /** Returns false when the output is not 16-bit PCM or has no channels or rate, so there is nothing to read. */
        fun formatChanged(format: MediaFormat): Boolean {
            val encoding = format.intOr(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            channels = format.intOr(MediaFormat.KEY_CHANNEL_COUNT, 0)
            val rate = format.intOr(MediaFormat.KEY_SAMPLE_RATE, 0)
            if (encoding != AudioFormat.ENCODING_PCM_16BIT || channels <= 0 || rate <= 0) return false
            peaks = PeakBins(bins, totalFrames = durationMs * rate / MILLIS_PER_SECOND)
            return true
        }

        fun consume(
            buffer: ByteBuffer?,
            info: MediaCodec.BufferInfo,
        ) {
            val target = peaks ?: return
            if (buffer == null || info.size <= 0) return
            buffer.position(info.offset)
            buffer.limit(info.offset + info.size)
            val samples = buffer.slice().order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
            val frames = samples.remaining() / channels
            for (index in 0 until frames) {
                var loudest = 0
                for (channel in 0 until channels) {
                    loudest = maxOf(loudest, abs(samples.get(index * channels + channel).toInt()))
                }
                target.add(frame + index, loudest)
            }
            frame += frames
        }

        fun result(): List<Int>? = peaks?.scaled()

        private fun MediaFormat.intOr(
            key: String,
            default: Int,
        ): Int = if (containsKey(key)) getInteger(key) else default
    }
}
