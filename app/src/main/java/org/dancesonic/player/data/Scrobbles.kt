package org.dancesonic.player.data

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.dancesonic.player.data.db.DanceSonicDatabase
import org.dancesonic.player.data.db.ScrobbleQueueEntity
import org.dancesonic.player.subsonic.PlayReporter
import org.dancesonic.player.subsonic.ServerSettings
import org.dancesonic.player.subsonic.SubsonicException
import retrofit2.HttpException
import java.io.IOException

/**
 * Plays waiting to be reported to folksonic (#89). A play stays in the queue until the server accepts it, so plays
 * made offline go out later, in order, each with the time it started. Pending plays belong to the server they were
 * made on: they are only sent there, and [forgetAll] drops them when the connection changes or the user signs out.
 */
class Scrobbles(
    private val database: DanceSonicDatabase,
    private val currentServer: () -> ServerSettings?,
    private val reporterFor: (ServerSettings) -> PlayReporter,
) {
    /** Keeps the queue consistent when a record and a send overlap. */
    private val mutex = Mutex()

    /** Keeps a counted play, then sends what is pending. Without a server, the play is not kept. */
    suspend fun record(
        songId: String,
        startedAtMs: Long,
    ) {
        val settings = currentServer() ?: return
        mutex.withLock {
            val dao = database.scrobbleQueueDao()
            dao.insert(
                ScrobbleQueueEntity(
                    serverUrl = settings.baseUrl.toString(),
                    songId = songId,
                    playedAt = startedAtMs,
                ),
            )
            dao.keepNewest(MAX_PENDING)
        }
        flush()
    }

    /**
     * Sends the pending plays of the current server, oldest first. A play is removed once the server has answered
     * for it. Sending stops at the first play that could not be reached, so the order is kept and it is tried again
     * later.
     */
    suspend fun flush() {
        val settings = currentServer() ?: return
        val server = settings.baseUrl.toString()
        mutex.withLock {
            val dao = database.scrobbleQueueDao()
            val reporter = reporterFor(settings)
            for (play in dao.pending(server)) {
                try {
                    reporter.scrobble(play.songId, play.playedAt)
                } catch (e: SubsonicException) {
                    if (e.isRetryable()) return
                    // The server answered and refused the play (for example, an unknown song). Repeating it would
                    // not help, so it is dropped rather than blocking the plays behind it.
                }
                dao.delete(play.id)
            }
        }
    }

    /** Drops every pending play, for a connection to another server or a sign-out. */
    suspend fun forgetAll() {
        mutex.withLock { database.scrobbleQueueDao().deleteAll() }
    }

    /** No contact with the server, or a server or rate-limit error that may pass. Other failures will not change. */
    private fun SubsonicException.isRetryable(): Boolean =
        when (val cause = cause) {
            is HttpException -> cause.code() == HTTP_TOO_MANY_REQUESTS || cause.code() >= HTTP_SERVER_ERROR
            is IOException -> true
            else -> false
        }

    companion object {
        /** Most plays kept while offline. The oldest go first, so a long offline stretch keeps its latest plays. */
        const val MAX_PENDING = 500

        private const val HTTP_TOO_MANY_REQUESTS = 429
        private const val HTTP_SERVER_ERROR = 500
    }
}
