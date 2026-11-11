package org.dancesonic.player.analysis

/** The largest value of a waveform peak. Peaks are stored as 0..[PEAK_MAX]. */
const val PEAK_MAX = 255

/**
 * Keeps the loudest sample of each of [bins] equal slices of a track of [totalFrames] frames. Frames past the end fall
 * into the last slice. [scaled] then stretches the slices so the loudest one is [PEAK_MAX].
 */
internal class PeakBins(
    private val bins: Int,
    private val totalFrames: Long,
) {
    init {
        require(bins > 0) { "bins must be positive" }
        require(totalFrames > 0) { "totalFrames must be positive" }
    }

    private val maxima = IntArray(bins)

    /** Records [peak], the absolute value of a sample in [frame]. */
    fun add(
        frame: Long,
        peak: Int,
    ) {
        val bin = (frame * bins / totalFrames).coerceIn(0L, (bins - 1).toLong()).toInt()
        if (peak > maxima[bin]) maxima[bin] = peak
    }

    /** One value per slice in 0..[PEAK_MAX]. A silent track gives all zeros. */
    fun scaled(): List<Int> {
        val top = maxima.max()
        if (top <= 0) return List(bins) { 0 }
        return maxima.map { it * PEAK_MAX / top }
    }
}

/** The storage form of a waveform: comma-separated values. */
internal fun encodePeaks(peaks: List<Int>): String = peaks.joinToString(",")

/** Reads [encodePeaks] output. Malformed text gives an empty waveform, which the seek bar treats as none. */
internal fun decodePeaks(text: String): List<Int> =
    if (text.isEmpty()) {
        emptyList()
    } else {
        text.split(',').map { it.toIntOrNull()?.coerceIn(0, PEAK_MAX) ?: return emptyList() }
    }
