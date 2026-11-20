package org.dancesonic.player.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

/** Plays waiting to be reported. Rows are sent oldest first, so the server sees them in the order they were played. */
@Dao
interface ScrobbleQueueDao {
    @Insert
    suspend fun insert(row: ScrobbleQueueEntity)

    @Query("SELECT * FROM scrobble_queue WHERE serverUrl = :serverUrl ORDER BY id")
    suspend fun pending(serverUrl: String): List<ScrobbleQueueEntity>

    @Query("DELETE FROM scrobble_queue WHERE id = :id")
    suspend fun delete(id: Long)

    /** Drops the oldest plays so that at most [keep] remain. */
    @Query("DELETE FROM scrobble_queue WHERE id NOT IN (SELECT id FROM scrobble_queue ORDER BY id DESC LIMIT :keep)")
    suspend fun keepNewest(keep: Int)

    @Query("DELETE FROM scrobble_queue")
    suspend fun deleteAll()
}
