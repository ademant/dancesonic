package org.dancesonic.player.analysis

/** The largest onset strength. Strengths are 0..[ONSET_STRENGTH_MAX], as folksonic serves them. */
const val ONSET_STRENGTH_MAX = 255

/**
 * Where notes start in a song, from folksonic's analysis: [timesMs] ascending, each with a strength in
 * 0..[ONSET_STRENGTH_MAX] at the same index of [strengths]. The time-stretch uses them to keep attacks sharp.
 */
class Onsets private constructor(
    val timesMs: IntArray,
    val strengths: IntArray,
) {
    val size: Int get() = timesMs.size

    companion object {
        /** No onsets: the server was asked and had none. */
        val NONE = Onsets(IntArray(0), IntArray(0))

        /**
         * Onsets from the server's lists, sorted by time. Negative times are dropped and strengths are clamped.
         * Null when the two lists differ in length, since the strengths then cannot be matched to the times.
         */
        fun of(
            timesMs: List<Long>,
            strengths: List<Int>,
        ): Onsets? {
            if (timesMs.size != strengths.size) return null
            val pairs =
                timesMs.indices
                    .filter { timesMs[it] in 0..Int.MAX_VALUE.toLong() }
                    .map { timesMs[it].toInt() to strengths[it].coerceIn(0, ONSET_STRENGTH_MAX) }
                    .sortedBy { it.first }
            return Onsets(IntArray(pairs.size) { pairs[it].first }, IntArray(pairs.size) { pairs[it].second })
        }
    }
}

/** The storage form of onset times or strengths: comma-separated values. */
internal fun encodeInts(values: IntArray): String = values.joinToString(",")

/** Reads [encodeInts] output. Malformed text gives null. */
internal fun decodeInts(text: String): IntArray? {
    if (text.isEmpty()) return IntArray(0)
    val parts = text.split(',')
    val values = IntArray(parts.size)
    for (i in parts.indices) values[i] = parts[i].toIntOrNull() ?: return null
    return values
}

/** Stored onsets, or null when the stored text is malformed. */
internal fun decodeOnsets(
    timesMs: String,
    strengths: String,
): Onsets? {
    val times = decodeInts(timesMs) ?: return null
    val levels = decodeInts(strengths) ?: return null
    return Onsets.of(times.map { it.toLong() }, levels.toList())
}
