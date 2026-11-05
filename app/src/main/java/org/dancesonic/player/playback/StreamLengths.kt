package org.dancesonic.player.playback

import org.dancesonic.player.data.db.TrackEntity
import java.util.concurrent.ConcurrentHashMap

/**
 * The file size of each song that has been queued for playback, keyed by song id. The stream source reads it
 * to bound each request, so the audio cache can see that a file is complete. In memory only: after a restart
 * the sizes come back with the next play, and until then streams are simply not bounded.
 */
class StreamLengths {
    private val sizes = ConcurrentHashMap<String, Long>()

    /** Records the sizes of [tracks] that have one. */
    fun remember(tracks: Collection<TrackEntity>) {
        tracks.forEach { track -> track.sizeBytes?.let { sizes[track.id] = it } }
    }

    /** The file size of [songId] in bytes, or null when it is not known. */
    fun sizeOf(songId: String): Long? = sizes[songId]
}
