package org.dancesonic.player.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/** Changes to DanceBrainz waiting to be sent, or held for review. */
@Dao
interface EditQueueDao {
    @Query("SELECT * FROM edit_queue ORDER BY queuedAt, id")
    fun observeAll(): Flow<List<EditEntity>>

    /** The changes still to be sent, oldest first, so they go out in the order the user made them. */
    @Query("SELECT * FROM edit_queue WHERE status = :status ORDER BY queuedAt, id")
    suspend fun withStatus(status: String): List<EditEntity>

    @Insert
    suspend fun insert(edit: EditEntity): Long

    /** Queues a shoes change, replacing any older queued shoes change of the same recording. */
    @Transaction
    suspend fun replaceShoes(edit: EditEntity) {
        deleteShoesFor(edit.mbid)
        insert(edit)
    }

    @Query("DELETE FROM edit_queue WHERE kind = 'shoes' AND mbid = :mbid AND status = 'queued'")
    suspend fun deleteShoesFor(mbid: String)

    @Query("DELETE FROM edit_queue WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("UPDATE edit_queue SET status = :status, lastError = NULL WHERE id = :id")
    suspend fun markStatus(
        id: Long,
        status: String,
    )

    @Query("UPDATE edit_queue SET lastError = :error WHERE id = :id")
    suspend fun recordError(
        id: Long,
        error: String,
    )

    @Query("SELECT COUNT(*) FROM edit_queue WHERE status = 'queued'")
    suspend fun queuedCount(): Int

    @Query("DELETE FROM edit_queue")
    suspend fun deleteAll()
}
