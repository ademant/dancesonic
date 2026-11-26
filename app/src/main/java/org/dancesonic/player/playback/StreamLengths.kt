package org.dancesonic.player.playback

import org.dancesonic.player.data.db.TrackEntity
import java.util.concurrent.ConcurrentHashMap

/**
 * The file size of each song, keyed by song id. The stream source reads it to bound each request, so the audio cache
 * can see that a file is complete and never asks for bytes past its end. Filled from the whole stored library (see
 * [replaceAll]), so a track started from a restored session or a media button is bounded too, not only one queued in
 * the app (#154). In memory only.
 */
class StreamLengths {
    private val sizes = ConcurrentHashMap<String, Long>()

    /** Records the sizes of [tracks] that have one. */
    fun remember(tracks: Collection<TrackEntity>) {
        tracks.forEach { track -> track.sizeBytes?.let { sizes[track.id] = it } }
    }

    /** Replaces every known size with [sizesById], such as the sizes of all stored tracks. */
    fun replaceAll(sizesById: Map<String, Long>) {
        sizes.keys.retainAll(sizesById.keys)
        sizes.putAll(sizesById)
    }

    /** The file size of [songId] in bytes, or null when it is not known. */
    fun sizeOf(songId: String): Long? = sizes[songId]
}
