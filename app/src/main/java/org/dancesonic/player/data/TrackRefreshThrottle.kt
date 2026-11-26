package org.dancesonic.player.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Limits the one-track refreshes that replace a catalog-wide sync on standard Subsonic servers (#121). A track is
 * asked for at most once per [cooldownMillis]; a refresh asked while one for the same track runs waits for that one
 * instead of sending another request. Remembers the [max] most recent tracks.
 */
class TrackRefreshThrottle(
    private val clock: () -> Long = System::currentTimeMillis,
    private val cooldownMillis: Long = DEFAULT_COOLDOWN_MILLIS,
    private val max: Int = DEFAULT_MAX,
) {
    private val lock = Mutex()
    private val lastRun =
        object : LinkedHashMap<String, Long>(max, LOAD_FACTOR, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?): Boolean = size > max
        }
    private val running = HashMap<String, CompletableDeferred<Unit>>()

    /**
     * Runs [refresh] for [songId] unless one ran within the cooldown, or joins the one that runs now. Returns true when
     * this call ran it. A failed refresh counts too, so a server that keeps failing is not asked again at once.
     */
    suspend fun run(
        songId: String,
        refresh: suspend () -> Unit,
    ): Boolean {
        val (own, joined) =
            lock.withLock {
                running[songId]?.let { return@withLock null to it }
                val last = lastRun[songId]
                if (last != null && clock() - last < cooldownMillis) return false
                lastRun[songId] = clock()
                CompletableDeferred<Unit>().also { running[songId] = it } to null
            }
        if (joined != null) {
            joined.await()
            return false
        }
        checkNotNull(own)
        try {
            refresh()
        } finally {
            lock.withLock { running.remove(songId) }
            own.complete(Unit)
        }
        return true
    }

    companion object {
        /** Six hours: a track played through a dance evening is asked for once. */
        const val DEFAULT_COOLDOWN_MILLIS = 6L * 60 * 60 * 1000
        private const val DEFAULT_MAX = 512
        private const val LOAD_FACTOR = 0.75f
    }
}
