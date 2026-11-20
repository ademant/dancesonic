package org.dancesonic.player.playback

/**
 * Decides when a track counts as a play (#89): once it has played for half its length, or four minutes, whichever
 * comes first. Only time spent playing counts, in track time, so a faster tempo counts sooner. A track that is left
 * before it counts is not counted, and a track of unknown length never counts.
 *
 * [tick] is called regularly while a track plays. A gap between ticks longer than [MAX_TICK_GAP_MS] (the app was
 * suspended, say) adds only that much, so it cannot count as playing.
 */
class PlayCounter(
    private val clock: () -> Long,
    private val onCounted: (songId: String, startedAtMs: Long) -> Unit,
) {
    private var songId: String? = null
    private var startedAtMs = 0L
    private var durationMs = 0L
    private var playedMs = 0.0
    private var counted = false
    private var lastTickMs: Long? = null

    /** A track began. A track that had not counted yet is dropped. */
    fun trackStarted(
        songId: String,
        durationMs: Long,
    ) {
        this.songId = songId
        this.durationMs = durationMs
        startedAtMs = clock()
        playedMs = 0.0
        counted = false
        lastTickMs = null
    }

    /** Playback has no track now, for example when the queue ended. */
    fun stopped() {
        songId = null
        lastTickMs = null
    }

    /** Adds the time since the last tick, if the track is playing at [speed]. */
    fun tick(
        playing: Boolean,
        speed: Float,
    ) {
        val id = songId ?: return
        val now = clock()
        val last = lastTickMs
        lastTickMs = now
        if (last == null || !playing || !canCount()) return
        val gapMs = (now - last).coerceIn(0L, MAX_TICK_GAP_MS)
        playedMs += gapMs * speed
        if (playedMs >= thresholdMs()) {
            counted = true
            onCounted(id, startedAtMs)
        }
    }

    private fun canCount(): Boolean = !counted && durationMs > 0

    /** Half the track, or four minutes, whichever is less. */
    private fun thresholdMs(): Long = minOf(durationMs / 2, FOUR_MINUTES_MS)

    companion object {
        const val FOUR_MINUTES_MS = 4 * 60 * 1000L
        const val MAX_TICK_GAP_MS = 2_000L
    }
}
