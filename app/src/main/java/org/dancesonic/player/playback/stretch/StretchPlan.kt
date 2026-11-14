package org.dancesonic.player.playback.stretch

import org.dancesonic.player.analysis.Onsets
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToLong

/**
 * How one stream is stretched. [blockSamples] and [intervalSamples] are the stretcher's analysis block and hop; 0
 * keeps its default. [breakIn] and [breakOut] are the time map's breakpoints: input frames and the output frames
 * they map to, both counted from the start of the stream. The map is linear between breakpoints and continues at
 * 1 / speed past the last one. Empty breakpoints stretch uniformly.
 */
class StretchPlan(
    val blockSamples: Int,
    val intervalSamples: Int,
    val breakIn: LongArray,
    val breakOut: DoubleArray,
) {
    init {
        require(breakIn.size == breakOut.size) { "Breakpoint lists differ in length" }
    }

    companion object {
        /** The default block, stretched uniformly: what a track without onsets gets. */
        val UNIFORM = StretchPlan(0, 0, LongArray(0), DoubleArray(0))
    }
}

/**
 * Plans the stretch of a stream from the song's onsets, so attacks keep close to their original speed.
 *
 * A spectral stretcher softens an attack when the analysis blocks around it are spread apart. Around each strong
 * onset the plan plays at 1x: a window from half a block before the onset to a sixth of a block after it, which covers
 * the blocks that see the attack. The time between onsets stretches more (or, when speeding up, less) to make up for
 * it. The map is split into segments, one per onset window, that meet halfway between windows; at each meeting point
 * the map is exactly on the nominal timeline (input / speed), so the variation never drifts and the position stays
 * right. Where onsets are too close for the gaps to make up the difference within [MAX_GAP_FACTOR], the windows move
 * towards the nominal speed instead.
 *
 * The block length follows how percussive the track is, judged from how many strong onsets it has per second:
 * shorter blocks for many attacks, longer ones for legato and drones. The numbers are a first guess, to be tuned by
 * listening.
 */
object StretchPlanner {
    /** Onsets below this strength (0..255) do not get a window. */
    const val STRENGTH_THRESHOLD = 80

    /** The gaps stretch at most this many times the nominal rate (or this many times less, when faster). */
    const val MAX_GAP_FACTOR = 1.6

    private const val UNITY_THRESHOLD = 0.0001
    private const val WINDOW_BEFORE = 0.5
    private const val WINDOW_AFTER = 0.17
    private const val INTERVALS_PER_BLOCK = 4
    private const val MIN_ONSETS_FOR_DENSITY = 8
    private const val PERCUSSIVE_PER_SECOND = 3.0
    private const val LEGATO_PER_SECOND = 1.0
    private const val PERCUSSIVE_BLOCK_SECONDS = 0.09
    private const val DEFAULT_BLOCK_SECONDS = 0.12
    private const val LEGATO_BLOCK_SECONDS = 0.15
    private const val MICROS_PER_MILLI = 1_000.0
    private const val MILLIS_PER_SECOND = 1_000.0

    /**
     * The plan for a stream that starts at [streamStartUs] into the song, at [speed], for audio at [sampleRate].
     * Without onsets, it is [StretchPlan.UNIFORM].
     */
    fun plan(
        onsets: Onsets?,
        streamStartUs: Long,
        speed: Float,
        sampleRate: Int,
    ): StretchPlan {
        if (onsets == null || onsets.size == 0 || abs(speed - 1.0) < UNITY_THRESHOLD) return StretchPlan.UNIFORM
        val blockSamples = (blockSeconds(onsets) * sampleRate).toInt()
        val startFrames = streamStartUs / MICROS_PER_MILLI * sampleRate / MILLIS_PER_SECOND
        val onsetFrames = DoubleArray(onsets.size) { onsets.timesMs[it] * sampleRate / MILLIS_PER_SECOND - startFrames }
        val windows = windows(onsetFrames, onsets.strengths, blockSamples)
        val (breakIn, breakOut) = timeMap(windows, 1.0 / speed)
        return StretchPlan(blockSamples, blockSamples / INTERVALS_PER_BLOCK, breakIn, breakOut)
    }

    /** The analysis block for a track with these onsets, in seconds. */
    internal fun blockSeconds(onsets: Onsets): Double {
        val strong = onsets.timesMs.indices.filter { onsets.strengths[it] >= STRENGTH_THRESHOLD }
        if (strong.size < MIN_ONSETS_FOR_DENSITY) return DEFAULT_BLOCK_SECONDS
        val spanSeconds = (onsets.timesMs[strong.last()] - onsets.timesMs[strong.first()]) / MILLIS_PER_SECOND
        if (spanSeconds <= 0.0) return DEFAULT_BLOCK_SECONDS
        val perSecond = (strong.size - 1) / spanSeconds
        return when {
            perSecond >= PERCUSSIVE_PER_SECOND -> PERCUSSIVE_BLOCK_SECONDS
            perSecond < LEGATO_PER_SECOND -> LEGATO_BLOCK_SECONDS
            else -> DEFAULT_BLOCK_SECONDS
        }
    }

    /**
     * The protected windows, in input frames from the stream start, for the strong onsets at [onsetFrames]
     * (ascending). Windows that overlap are merged; a window that began before the stream starts at 0.
     */
    internal fun windows(
        onsetFrames: DoubleArray,
        strengths: IntArray,
        blockSamples: Int,
    ): List<LongRange> {
        val before = blockSamples * WINDOW_BEFORE
        val after = blockSamples * WINDOW_AFTER
        val windows = mutableListOf<LongRange>()
        for (i in onsetFrames.indices) {
            if (strengths[i] < STRENGTH_THRESHOLD) continue
            val start = max(0L, (onsetFrames[i] - before).roundToLong())
            val end = (onsetFrames[i] + after).roundToLong()
            if (end <= start) continue
            val last = windows.lastOrNull()
            if (last != null && start <= last.last) {
                windows[windows.size - 1] = last.first..max(last.last, end)
            } else {
                windows += start..end
            }
        }
        return windows
    }

    /**
     * Breakpoints for [windows] at [nominal] output frames per input frame (1 / speed). Each window plays at 1x when
     * its segment can make up the difference, and the segment as a whole always lasts exactly its nominal length.
     */
    internal fun timeMap(
        windows: List<LongRange>,
        nominal: Double,
    ): Pair<LongArray, DoubleArray> {
        if (windows.isEmpty()) return LongArray(0) to DoubleArray(0)
        val breakIn = mutableListOf(0L)
        val breakOut = mutableListOf(0.0)

        fun add(
            frame: Long,
            out: Double,
        ) {
            if (frame <= breakIn.last()) return
            breakIn += frame
            breakOut += max(out, breakOut.last())
        }

        var segmentStart = 0L
        for ((i, window) in windows.withIndex()) {
            val next = windows.getOrNull(i + 1)
            val windowLength = window.last - window.first
            val segmentEnd = if (next != null) (window.last + next.first) / 2 else window.last + windowLength
            val slopes = slopes(segmentEnd - segmentStart, windowLength, nominal)
            val windowStartOut = segmentStart * nominal + (window.first - segmentStart) * slopes.gap
            add(window.first, windowStartOut)
            add(window.last, windowStartOut + windowLength * slopes.window)
            add(segmentEnd, segmentEnd * nominal)
            segmentStart = segmentEnd
        }
        return breakIn.toLongArray() to breakOut.toDoubleArray()
    }

    /** Output frames per input frame inside a window and in the rest of its segment. */
    internal data class Slopes(
        val window: Double,
        val gap: Double,
    )

    /**
     * Slopes for a segment of [length] input frames holding a window of [windowLength] frames, so the segment lasts
     * [length] * [nominal] output frames. The window plays at 1x unless the gap would need more than
     * [MAX_GAP_FACTOR] times the nominal rate to make up for it.
     */
    internal fun slopes(
        length: Long,
        windowLength: Long,
        nominal: Double,
    ): Slopes {
        val gapLength = length - windowLength
        if (gapLength <= 0 || windowLength <= 0) return Slopes(nominal, nominal)
        val target = length * nominal
        val (gapMin, gapMax) =
            if (nominal > 1.0) nominal to nominal * MAX_GAP_FACTOR else nominal / MAX_GAP_FACTOR to nominal
        val gap = (target - windowLength) / gapLength
        if (gap in gapMin..gapMax) return Slopes(1.0, gap)
        val clamped = gap.coerceIn(gapMin, gapMax)
        return Slopes((target - gapLength * clamped) / windowLength, clamped)
    }
}
