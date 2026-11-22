package org.dancesonic.player.ui

import org.dancesonic.player.data.db.TrackWithTempo

/**
 * How the tracks of a dance are ordered on the Dance tab. [ARTIST_TITLE] is the order the library gives: artist, then
 * title.
 */
internal enum class TrackSort(val label: String) {
    ARTIST_TITLE("Artist"),
    LENGTH_ASC("Length asc"),
    LENGTH_DESC("Length desc"),
}

/**
 * The tracks in [sort] order. Tracks with no known length come last in both length orders, and tracks of the same
 * length keep the order they came in.
 */
internal fun sortTracks(
    tracks: List<TrackWithTempo>,
    sort: TrackSort,
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
    }
