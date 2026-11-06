package org.dancesonic.player.data

import org.dancesonic.player.data.db.TrackEntity

/**
 * What the app shows about one track: the stored track, its album name, its tempo (folksonic's, with DanceBrainz as
 * the fallback, as in the track rows) and its dance types. Read from the local database only.
 */
data class TrackDetail(
    val track: TrackEntity,
    val albumName: String?,
    val bpm: Int?,
    val danceTypes: List<String>,
)
