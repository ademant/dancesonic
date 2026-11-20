package org.dancesonic.player.studio

/** Pure rules for showing and steering a Studio's playback from the phone. */
object StudioPlayback {
    /** The speeds Studio accepts for `set_rate`. */
    const val MIN_RATE = 0.5
    const val MAX_RATE = 1.5

    /** The most songs one `play_songs` or `enqueue` command may carry. */
    const val MAX_SONGS_PER_COMMAND = 1000

    /** The rate Studio is asked for when the phone asks for [speed]. */
    fun rateFor(speed: Float): Double = speed.toDouble().coerceIn(MIN_RATE, MAX_RATE)

    /**
     * Where Studio's playback is now. Studio reports the position when it changes and every few seconds while
     * playing, so the phone moves it on by the time since that report, at the reported rate, and never past the
     * length of the song.
     */
    fun positionAt(
        state: StudioPlayState,
        reportedAtMs: Long,
        nowMs: Long,
    ): Long {
        if (!state.playing) return state.positionMs
        val elapsedMs = (nowMs - reportedAtMs).coerceAtLeast(0L)
        val moved = state.positionMs + (elapsedMs * state.rate).toLong()
        return state.lengthMs?.let { moved.coerceAtMost(it) } ?: moved
    }

    /** The songs to send in one command: the first [MAX_SONGS_PER_COMMAND]. */
    fun songsForCommand(songs: List<String>): List<String> = songs.take(MAX_SONGS_PER_COMMAND)
}
