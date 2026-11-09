package org.dancesonic.player.data

import androidx.room.withTransaction
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.HttpUrl
import org.dancesonic.player.dance.ChapterChange
import org.dancesonic.player.dance.ChapterList
import org.dancesonic.player.dance.DanceBrainzChaptersClient
import org.dancesonic.player.dance.DanceBrainzException
import org.dancesonic.player.dance.DanceBrainzTokenStore
import org.dancesonic.player.dance.TagWriteResult
import org.dancesonic.player.data.db.DanceSonicDatabase

/**
 * Chapter marks on DanceBrainz, changed from the Now Playing sheet. Unlike shoes and dance tags, a chapter change is
 * sent at once and not queued: its position depends on the list as it is at that moment. Each write reads the
 * current list first, so its version is fresh, and reloads the list after it.
 */
class ChapterEdits(
    private val database: DanceSonicDatabase,
    private val tokens: DanceBrainzTokenStore,
    private val baseUrl: () -> HttpUrl,
    private val newClient: (HttpUrl) -> DanceBrainzChaptersClient = { DanceBrainzChaptersClient(it) },
) {
    private val writeLock = Mutex()

    /** Reads the chapters of [mbid] from DanceBrainz and stores them, replacing the stored list. */
    suspend fun refresh(mbid: String) {
        store(mbid, newClient(baseUrl()).read(mbid))
    }

    /** Marks a chapter at [startMs], with no title or dance yet. Returns true when DanceBrainz holds it for review. */
    suspend fun add(
        mbid: String,
        startMs: Long,
    ): Boolean = write(mbid) { client, version, token -> client.add(mbid, startMs, version, token) }

    /** Applies [change] to the chapter starting at [startMs]. Returns true when DanceBrainz holds it for review. */
    suspend fun update(
        mbid: String,
        startMs: Long,
        change: ChapterChange,
    ): Boolean = write(mbid) { client, version, token -> client.update(mbid, startMs, change, version, token) }

    /** Removes the chapter starting at [startMs]. Returns true when DanceBrainz holds it for review. */
    suspend fun delete(
        mbid: String,
        startMs: Long,
    ): Boolean = write(mbid) { client, version, token -> client.delete(mbid, startMs, version, token) }

    /**
     * Sends one write against the current version of the list, then stores the list as it is after the write. A
     * write refused with 412 reloads the list before the error reaches the user, so the next try starts from it.
     */
    private suspend fun write(
        mbid: String,
        send: suspend (DanceBrainzChaptersClient, String?, String) -> TagWriteResult,
    ): Boolean =
        writeLock.withLock {
            val token = tokens.credential.first()?.token ?: throw DanceBrainzException(NOT_SIGNED_IN)
            val client = newClient(baseUrl())
            val current = client.read(mbid)
            val result =
                try {
                    send(client, current.version, token)
                } catch (e: DanceBrainzException) {
                    if (e.httpCode == PRECONDITION_FAILED) store(mbid, client.read(mbid))
                    throw e
                }
            store(mbid, client.read(mbid))
            result.pending
        }

    private suspend fun store(
        mbid: String,
        list: ChapterList,
    ) {
        database.withTransaction {
            database.chapterDao().replaceFor(mbid, list.chapters.map { it.toEntity(mbid) })
            database.danceLookupDao().setChaptersVersion(mbid, list.version)
        }
    }

    private companion object {
        const val PRECONDITION_FAILED = 412
        const val NOT_SIGNED_IN = "Sign in to DanceBrainz to change chapters"
    }
}
