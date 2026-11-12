package org.dancesonic.player.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.HttpUrl
import org.dancesonic.player.dance.DanceBrainzCredential
import org.dancesonic.player.dance.DanceBrainzException
import org.dancesonic.player.dance.DanceBrainzRatingsClient
import org.dancesonic.player.dance.DanceBrainzTagsClient
import org.dancesonic.player.dance.DanceBrainzTokenStore
import org.dancesonic.player.dance.DanceSuggestion
import org.dancesonic.player.data.db.DanceMetadataDao
import org.dancesonic.player.data.db.DanceSonicDatabase
import org.dancesonic.player.data.db.EditEntity
import org.dancesonic.player.data.db.EditKind
import org.dancesonic.player.data.db.EditQueueDao
import org.dancesonic.player.data.db.EditStatus
import org.dancesonic.player.data.db.ShoesRatingDao
import org.dancesonic.player.data.db.ShoesRatingEntity

/**
 * What the phone shows for one recording's shoes rating. [mine] already includes a queued change, so a rating set
 * offline is visible at once. [pending] is true while a change waits to be sent; [error] is why the last send failed.
 */
data class ShoesView(
    val mine: Int?,
    val median: Double?,
    val raters: Int,
    val pending: Boolean,
    val error: String?,
)

/** The two DanceBrainz clients the edits use, for one server. */
data class DanceBrainzClients(
    val ratings: DanceBrainzRatingsClient,
    val tags: DanceBrainzTagsClient,
)

/**
 * DanceBrainz changes made on this phone: shoes ratings and dance tags. Every change is applied locally at once and
 * queued in one queue, then sent in order. A failed send stays queued with its error. A change the server holds for
 * review stays in the queue as pending review until the user dismisses it.
 */
class DanceBrainzEdits(
    database: DanceSonicDatabase,
    private val tokens: DanceBrainzTokenStore,
    private val baseUrl: () -> HttpUrl,
    private val newClients: (HttpUrl) -> DanceBrainzClients = {
        DanceBrainzClients(DanceBrainzRatingsClient(it), DanceBrainzTagsClient(it))
    },
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val ratings: ShoesRatingDao = database.shoesRatingDao()
    private val queue: EditQueueDao = database.editQueueDao()
    private val danceMetadata: DanceMetadataDao = database.danceMetadataDao()
    private val flushLock = Mutex()

    /** The shoes state of every recording with a rating read or a shoes change queued, by MBID. */
    fun observeShoes(): Flow<Map<String, ShoesView>> =
        combine(ratings.observeAll(), queue.observeAll()) { rated, edits ->
            val byMbid = rated.associateBy { it.mbid }
            val queuedShoes = edits.filter { it.kind == EditKind.SHOES }.associateBy { it.mbid }
            (byMbid.keys + queuedShoes.keys).associateWith { mbid ->
                shoesView(byMbid[mbid], queuedShoes[mbid])
            }
        }

    /** Every change not yet confirmed by DanceBrainz, oldest first, including those held for review or in conflict. */
    fun observeQueue(): Flow<List<EditEntity>> = queue.observeAll()

    /** The dance names the phone has for [mbid], as the last lookup returned them. */
    fun observeDances(mbid: String): Flow<List<String>> = danceMetadata.observeDanceTypesFor(mbid)

    /** Sets the viewer's shoes rating of [mbid] to [value] (1 to 5), then tries to send it. */
    suspend fun rate(
        mbid: String,
        value: Int,
    ) {
        require(value in SHOES_RANGE) { "A shoes rating is from 1 to 5" }
        queueShoes(mbid, value)
        flush()
    }

    /** Clears the viewer's shoes rating of [mbid], then tries to send it. */
    suspend fun clearRating(mbid: String) {
        queueShoes(mbid, value = null)
        flush()
    }

    /** Queues adding the dance at [suggestion] with [qualifier] to [mbid], then tries to send it. */
    suspend fun addDance(
        mbid: String,
        suggestion: DanceSuggestion,
        qualifier: String,
    ) {
        require(MBID_PATTERN.matches(mbid)) { NOT_RATEABLE }
        require(qualifier in QUALIFIERS) { "A dance qualifier is learn, disco or both" }
        queue.insert(
            EditEntity(
                kind = EditKind.DANCE_ADD,
                mbid = mbid,
                path = suggestion.path,
                qualifier = qualifier,
                queuedAt = clock(),
            ),
        )
        flush()
    }

    /**
     * Queues removing the dance named [name] from [mbid], then tries to send it. The names the phone shows now are
     * kept, so the send can tell whether the track changed on DanceBrainz in the meantime.
     */
    suspend fun removeDance(
        mbid: String,
        name: String,
    ) {
        require(MBID_PATTERN.matches(mbid)) { NOT_RATEABLE }
        val shown = danceMetadata.danceTypesFor(mbid)
        queue.insert(
            EditEntity(
                kind = EditKind.DANCE_REMOVE,
                mbid = mbid,
                danceName = name,
                expectedDances = shown.joinToString(NAME_SEPARATOR),
                queuedAt = clock(),
            ),
        )
        flush()
    }

    /** Removes a change from the queue, such as a held change the user has seen. */
    suspend fun dismiss(id: Long) {
        queue.delete(id)
    }

    /**
     * Sends the queued changes in order. Returns how many are still waiting to be sent. With no answer from the
     * server the rest of the queue waits for the next try.
     */
    suspend fun flush(): Int =
        flushLock.withLock {
            val credential = tokens.credential.first() ?: return@withLock queue.queuedCount()
            val sender = EditSender(ratings, queue, newClients(baseUrl()), credential)
            for (edit in queue.withStatus(EditStatus.QUEUED)) {
                try {
                    sender.send(edit)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: DanceBrainzException) {
                    queue.recordError(edit.id, e.message ?: NOT_SENT)
                    if (e.httpCode == null) break
                }
            }
            queue.queuedCount()
        }

    /** Asks DanceBrainz for dance values that match [name]. Throws [DanceBrainzException] when it cannot. */
    suspend fun suggestDances(name: String): List<DanceSuggestion> = newClients(baseUrl()).tags.suggest(name)

    /**
     * Reads the shoes ratings of [mbids] that were not read in the last day, at most [MAX_REFRESH_PER_CALL] of them.
     * Returns how many reads failed. Works without a token; the viewer's own rating needs one.
     */
    suspend fun refreshShoes(mbids: List<String>): Int {
        val valid = mbids.filter { MBID_PATTERN.matches(it) }.distinct()
        val fresh = ratings.checkedSince(valid, clock() - MAX_AGE_MILLIS).toSet()
        val stale = valid.filterNot { it in fresh }.take(MAX_REFRESH_PER_CALL)
        if (stale.isEmpty()) return 0
        val credential = tokens.credential.first()
        val client = newClients(baseUrl()).ratings
        var failed = 0
        for ((index, mbid) in stale.withIndex()) {
            try {
                val read = client.ratings(mbid, credential)
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
     * Removes the ratings and the queue of the account being signed out. Returns how many changes were never sent,
     * so the caller can say so. Waits for a send in progress to finish first.
     */
    suspend fun forgetAccount(): Int =
        flushLock.withLock {
            val unsent = queue.queuedCount()
            queue.deleteAll()
            ratings.deleteAll()
            unsent
        }

    private suspend fun queueShoes(
        mbid: String,
        value: Int?,
    ) {
        require(MBID_PATTERN.matches(mbid)) { NOT_RATEABLE }
        queue.replaceShoes(EditEntity(kind = EditKind.SHOES, mbid = mbid, value = value, queuedAt = clock()))
    }

    private fun shoesView(
        rating: ShoesRatingEntity?,
        edit: EditEntity?,
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
        val QUALIFIERS = setOf("learn", "disco", "both")
        val MBID_PATTERN = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
        const val MAX_AGE_MILLIS = 24L * 60 * 60 * 1000
        const val MAX_REFRESH_PER_CALL = 25
        const val NOT_SENT = "The change was not sent."
        const val NOT_RATEABLE = "Only a recording with a MusicBrainz ID can be changed"
        const val NAME_SEPARATOR = "\n"
    }
}

/**
 * Sends one queued change to DanceBrainz with the account's credential. A send that succeeds leaves the queue, or is
 * held as pending review; a removal first checks the track, and is marked as a conflict if it changed.
 */
private class EditSender(
    private val ratings: ShoesRatingDao,
    private val queue: EditQueueDao,
    private val clients: DanceBrainzClients,
    private val credential: DanceBrainzCredential,
) {
    suspend fun send(edit: EditEntity) {
        when (edit.kind) {
            EditKind.SHOES -> sendShoes(edit)
            EditKind.DANCE_ADD -> sendAdd(edit)
            EditKind.DANCE_REMOVE -> sendRemove(edit)
        }
    }

    private suspend fun sendShoes(edit: EditEntity) {
        val value = edit.value
        if (value == null) {
            clients.ratings.clearShoes(edit.mbid, credential)
        } else {
            clients.ratings.setShoes(edit.mbid, value, credential)
        }
        queue.delete(edit.id)
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

    private suspend fun sendAdd(edit: EditEntity) {
        val result = clients.tags.addDance(edit.mbid, edit.path.orEmpty(), edit.qualifier.orEmpty(), credential)
        settle(edit, held = result.pending)
    }

    private suspend fun sendRemove(edit: EditEntity) {
        val name = edit.danceName.orEmpty()
        val current = clients.tags.currentDances(edit.mbid, credential)
        if (current.none { it.name == name }) {
            queue.delete(edit.id)
            return
        }
        val shown = edit.expectedDances.orEmpty().split(NAME_SEPARATOR).filter { it.isNotEmpty() }
        val othersNow = current.map { it.name }.filter { it != name }.sorted()
        val othersShown = shown.filter { it != name }.sorted()
        if (othersNow != othersShown) {
            queue.markStatus(edit.id, EditStatus.CONFLICT)
            queue.recordError(edit.id, CONFLICT)
            return
        }
        val result = clients.tags.replaceDances(edit.mbid, current.filter { it.name != name }, credential)
        settle(edit, held = result.pending)
    }

    /** A confirmed change leaves the queue; a change the server holds for review stays, marked as such. */
    private suspend fun settle(
        edit: EditEntity,
        held: Boolean,
    ) {
        if (held) {
            queue.markStatus(edit.id, EditStatus.PENDING_REVIEW)
        } else {
            queue.delete(edit.id)
        }
    }

    private companion object {
        const val NAME_SEPARATOR = "\n"
        const val CONFLICT =
            "The dances changed on DanceBrainz since you saw them, so nothing was sent. " +
                "Dismiss this and remove the dance again if you still want it."
    }
}
