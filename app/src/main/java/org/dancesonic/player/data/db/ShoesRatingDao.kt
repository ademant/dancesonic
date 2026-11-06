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
