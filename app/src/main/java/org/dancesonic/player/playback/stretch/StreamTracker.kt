package org.dancesonic.player.playback.stretch

import kotlin.math.max

/** The song and media position of the audio that is entering the audio sink. [songId] is null when unknown. */
data class StreamPosition(
    val songId: String?,
    val mediaTimeUs: Long,
)

/**
 * Follows which song, and where in it, the audio sink is being fed, so the time-stretch can find the song's onsets.
 * Media3 does not tell audio processors this, so [TrackingAudioSink] reports the sink's calls here.
 *
 * A buffer's media time is its presentation time minus the renderer's output stream offset. The song is the queue's
 * current item when its stream starts after a flush (start, seek, new item), and the queue's next item when its
 * stream follows another one without a flush (a gapless transition, decoded before the player moves on).
 *
 * [setQueue] is called from the application thread; everything else from the playback thread.
 */
class StreamTracker {
    private data class Queue(
        val current: String?,
        val next: String?,
    )

    @Volatile
    private var queue = Queue(null, null)

    private var songId: String? = null
    private var streamOffsetUs = 0L
    private var hasOffset = false
    private var handledSinceFlush = false
    private var bufferTimeUs: Long? = null

    /** The ids of the item playing now and the one after it (respecting repeat and shuffle). */
    fun setQueue(
        currentSongId: String?,
        nextSongId: String?,
    ) {
        queue = Queue(currentSongId, nextSongId)
    }

    /** The sink was flushed: what comes next starts a new run of audio. */
    fun onFlush() {
        handledSinceFlush = false
        bufferTimeUs = null
    }

    /** A new stream (a song) starts; its media time is presentation time minus [offsetUs]. */
    fun onStreamOffset(offsetUs: Long) {
        if (hasOffset && offsetUs == streamOffsetUs) return
        val snapshot = queue
        songId = if (handledSinceFlush) snapshot.next else snapshot.current
        streamOffsetUs = offsetUs
        hasOffset = true
    }

    /** The sink is handed a buffer that starts at [presentationTimeUs]. */
    fun onBuffer(presentationTimeUs: Long) {
        if (!handledSinceFlush && songId == null) songId = queue.current
        handledSinceFlush = true
        bufferTimeUs = presentationTimeUs
    }

    /** The sink was reset; nothing is known until the next stream. */
    fun onReset() {
        songId = null
        hasOffset = false
        streamOffsetUs = 0L
        onFlush()
    }

    /** Where the buffer being handled starts, or null before the first buffer after a flush. */
    fun position(): StreamPosition? {
        val time = bufferTimeUs ?: return null
        return StreamPosition(songId, max(0L, time - streamOffsetUs))
    }
}
