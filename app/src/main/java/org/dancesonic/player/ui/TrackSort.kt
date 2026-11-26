package org.dancesonic.player.ui

import org.dancesonic.player.data.db.TrackWithTempo
import kotlin.random.Random

/**
 * How the tracks of a dance are ordered on the Dance tab. [ARTIST_TITLE] is the order the library gives: artist, then
 * title. [RANDOM] shuffles them.
 */
internal enum class TrackSort(
    val label: String,
) {
    ARTIST_TITLE("Artist"),
    LENGTH_ASC("Length asc"),
    LENGTH_DESC("Length desc"),
    RANDOM("Randomize"),
}

/**
 * The tracks in [sort] order. Tracks with no known length come last in both length orders, and tracks of the same
 * length keep the order they came in. [TrackSort.RANDOM] shuffles with [seed], so the same seed gives the same order:
 * the list keeps its order while it is shown, and a new seed gives a new one.
 */
internal fun sortTracks(
    tracks: List<TrackWithTempo>,
    sort: TrackSort,
    seed: Long = 0L,
): List<TrackWithTempo> =
    when (sort) {
        TrackSort.ARTIST_TITLE -> tracks
        TrackSort.LENGTH_ASC ->
            tracks.sortedWith(
                compareBy<TrackWithTempo> { it.track.durationSec == null }.thenBy { it.track.durationSec },
            )
        TrackSort.LENGTH_DESC ->
            tracks.sortedWith(
                compareBy<TrackWithTempo> { it.track.durationSec == null }.thenByDescending { it.track.durationSec },
            )
        TrackSort.RANDOM -> tracks.shuffled(Random(seed))
    }
