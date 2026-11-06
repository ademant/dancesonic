package org.dancesonic.player.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.HttpUrl
import org.dancesonic.player.dance.DanceBrainzException
import org.dancesonic.player.dance.DanceBrainzRatingsClient
import org.dancesonic.player.dance.DanceBrainzTokenStore
import org.dancesonic.player.data.db.ShoesEditDao
import org.dancesonic.player.data.db.ShoesEditEntity
import org.dancesonic.player.data.db.ShoesRatingDao
import org.dancesonic.player.data.db.ShoesRatingEntity

/**
 * What the phone shows for one recording's shoes rating. [mine] already includes a change that is still queued,
 * so a rating set offline is visible at once. [pending] is true while a change waits to be sent; [error] is why
 * the last send failed.
 */
data class ShoesView(
    val mine: Int?,
    val median: Double?,
    val raters: Int,
    val pending: Boolean,
    val error: String?,
)

/**
 * Shoes ratings for the DanceBrainz account on this phone. Changes are applied locally at once and queued, then
 * sent to DanceBrainz. A failed send stays queued with its error and is tried again on the next [flush].
 */
class ShoesRepository(
    private val ratings: ShoesRatingDao,
    private val edits: ShoesEditDao,
    private val tokens: DanceBrainzTokenStore,
    private val baseUrl: () -> HttpUrl,
    private val newClient: (HttpUrl) -> DanceBrainzRatingsClient = { DanceBrainzRatingsClient(it) },
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val flushLock = Mutex()

    @Volatile
    private var lastQueuedAt = 0L

    /** The shoes state of every recording that has a rating read or a change queued, by MBID. */
    fun observe(): Flow<Map<String, ShoesView>> =
        combine(ratings.observeAll(), edits.observeAll()) { rated, queued ->
            val byMbid = rated.associateBy { it.mbid }
            val queuedByMbid = queued.associateBy { it.mbid }
            (byMbid.keys + queuedByMbid.keys).associateWith { mbid ->
                viewOf(byMbid[mbid], queuedByMbid[mbid])
            }
        }

    /** Sets the viewer's rating of [mbid] to [value] (1 to 5), then tries to send it. */
    suspend fun rate(
        mbid: String,
        value: Int,
    ) {
        require(value in SHOES_RANGE) { "A shoes rating is from 1 to 5" }
        queue(mbid, value)
        flush()
    }

    /** Clears the viewer's rating of [mbid], then tries to send it. */
    suspend fun clear(mbid: String) {
        queue(mbid, null)
        flush()
    }

    /**
     * Sends the queued changes in the order they were made. A failed send keeps its change queued with the error.
     * With no answer from the server the rest of the queue is left for the next try. Returns how many changes
     * are still waiting.
     */
    suspend fun flush(): Int =
        flushLock.withLock {
            val credential = tokens.credential.first() ?: return@withLock edits.count()
            val client = newClient(baseUrl())
            for (edit in edits.all()) {
                try {
                    send(client, edit, credential.token)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: DanceBrainzException) {
                    edits.recordError(edit.mbid, edit.queuedAt, e.message ?: NOT_SENT)
                    if (e.httpCode == null) break
                }
            }
            edits.count()
        }

    /**
     * Reads the ratings of [mbids] that were not read in the last day, at most [MAX_REFRESH_PER_CALL] of them.
     * Returns how many reads failed. Works without a token; the viewer's own rating needs one.
     */
    suspend fun refresh(mbids: List<String>): Int {
        val valid = mbids.filter { MBID_PATTERN.matches(it) }.distinct()
        val fresh = ratings.checkedSince(valid, clock() - MAX_AGE_MILLIS).toSet()
        val stale = valid.filterNot { it in fresh }.take(MAX_REFRESH_PER_CALL)
        if (stale.isEmpty()) return 0
        val token = tokens.credential.first()?.token
        val client = newClient(baseUrl())
        var failed = 0
        for ((index, mbid) in stale.withIndex()) {
            try {
                val read = client.ratings(mbid, token)
                ratings.upsert(ShoesRatingEntity(mbid, read.myShoes, read.shoesMedian, read.shoesRaters, clock()))
            } catch (e: CancellationException) {
                throw e
            } catch (e: DanceBrainzException) {
                failed++
                if (e.httpCode == null) return failed + stale.size - index - 1
            }
        }
        return failed
    }

    /**
     * Removes the ratings and the queued changes of the account being signed out. Returns how many queued changes
     * were never sent, so the caller can say so. Waits for a send in progress to finish first.
     */
    suspend fun forgetAccount(): Int =
        flushLock.withLock {
            val unsent = edits.count()
            edits.deleteAll()
            ratings.deleteAll()
            unsent
        }

    private suspend fun queue(
        mbid: String,
        value: Int?,
    ) {
        require(MBID_PATTERN.matches(mbid)) { "Only a recording with a MusicBrainz ID can be rated" }
        edits.upsert(ShoesEditEntity(mbid, value, nextQueuedAt(), lastError = null))
    }

    /** Strictly increasing, so a change made during a send is never mistaken for the one that was sent. */
    private fun nextQueuedAt(): Long =
        synchronized(this) {
            val at = maxOf(clock(), lastQueuedAt + 1)
            lastQueuedAt = at
            at
        }

    private suspend fun send(
        client: DanceBrainzRatingsClient,
        edit: ShoesEditEntity,
        token: String,
    ) {
        val value = edit.value
        if (value == null) {
            client.clearShoes(edit.mbid, token)
        } else {
            client.setShoes(edit.mbid, value, token)
        }
        edits.delete(edit.mbid, edit.queuedAt)
        // The viewer's own value is now known; the median and count keep their last read until the next refresh.
        val before = ratings.get(edit.mbid)
        ratings.upsert(
            ShoesRatingEntity(
                mbid = edit.mbid,
                myShoes = value,
                median = before?.median,
                raters = before?.raters ?: 0,
                checkedAt = before?.checkedAt ?: 0L,
            ),
        )
    }

    private fun viewOf(
        rating: ShoesRatingEntity?,
        edit: ShoesEditEntity?,
    ): ShoesView =
        ShoesView(
            mine = if (edit != null) edit.value else rating?.myShoes,
            median = rating?.median,
            raters = rating?.raters ?: 0,
            pending = edit != null,
            error = edit?.lastError,
        )

    private companion object {
        val SHOES_RANGE = 1..5
        val MBID_PATTERN = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
        const val MAX_AGE_MILLIS = 24L * 60 * 60 * 1000
        const val MAX_REFRESH_PER_CALL = 25
        const val NOT_SENT = "The change was not sent."
    }
}
