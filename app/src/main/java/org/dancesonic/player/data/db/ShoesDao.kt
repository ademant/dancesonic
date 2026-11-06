package org.dancesonic.player.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** The shoes ratings last read from DanceBrainz. */
@Dao
interface ShoesRatingDao {
    @Query("SELECT * FROM shoes_rating")
    fun observeAll(): Flow<List<ShoesRatingEntity>>

    @Query("SELECT * FROM shoes_rating WHERE mbid = :mbid")
    suspend fun get(mbid: String): ShoesRatingEntity?

    /** The MBIDs among [mbids] whose rating was read at or after [since]. */
    @Query("SELECT mbid FROM shoes_rating WHERE mbid IN (:mbids) AND checkedAt >= :since")
    suspend fun checkedSince(
        mbids: List<String>,
        since: Long,
    ): List<String>

    @Upsert
    suspend fun upsert(row: ShoesRatingEntity)

    @Query("DELETE FROM shoes_rating")
    suspend fun deleteAll()
}

/** Shoes changes waiting to be sent to DanceBrainz. */
@Dao
interface ShoesEditDao {
    @Query("SELECT * FROM shoes_edit")
    fun observeAll(): Flow<List<ShoesEditEntity>>

    /** The queued changes, oldest first, so they are sent in the order the user made them. */
    @Query("SELECT * FROM shoes_edit ORDER BY queuedAt")
    suspend fun all(): List<ShoesEditEntity>

    @Upsert
    suspend fun upsert(row: ShoesEditEntity)

    /**
     * Removes a queued change once it is confirmed. Matching on [queuedAt] keeps a newer change for the same
     * recording, made while the send was in flight, in the queue.
     */
    @Query("DELETE FROM shoes_edit WHERE mbid = :mbid AND queuedAt = :queuedAt")
    suspend fun delete(
        mbid: String,
        queuedAt: Long,
    )

    @Query("UPDATE shoes_edit SET lastError = :error WHERE mbid = :mbid AND queuedAt = :queuedAt")
    suspend fun recordError(
        mbid: String,
        queuedAt: Long,
        error: String,
    )

    @Query("SELECT COUNT(*) FROM shoes_edit")
    suspend fun count(): Int

    @Query("DELETE FROM shoes_edit")
    suspend fun deleteAll()
}
