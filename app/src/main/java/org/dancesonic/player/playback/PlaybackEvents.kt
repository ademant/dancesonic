package org.dancesonic.player.playback

import org.dancesonic.player.data.db.TrackEntity
import org.dancesonic.player.subsonic.ServerSettings

/**
 * What playback reports to the app, called on the main thread. Each method does nothing by default, so a caller
 * overrides only what it needs.
 */
interface PlaybackEvents {
    /** A track began; [songId] is its id. */
    fun trackStarted(songId: String) = Unit

    /**
     * A new queue, before it starts: the tracks, the index that starts, and the server settings. Records the sizes
     * that bound the stream, and may start a download of the track that begins.
     */
    fun queued(
        queue: List<TrackEntity>,
        startIndex: Int,
        settings: ServerSettings,
    ) = Unit

    /** A track has played long enough to count as a play (#89), starting at [startedAtMs] (epoch milliseconds). */
    fun playCounted(
        songId: String,
        startedAtMs: Long,
    ) = Unit

    companion object {
        val None: PlaybackEvents = object : PlaybackEvents {}
    }
}
