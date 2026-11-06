package org.dancesonic.player.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * The tracks covered by kept albums and kept playlists. A playlist entry whose track the server has dropped matches
 * nothing, so it is not downloaded. The literals match [KeptKind].
 */
@Dao
interface KeptCollectionDao {
    @Query("SELECT EXISTS(SELECT 1 FROM kept_collection WHERE kind = :kind AND refId = :refId)")
    fun observeKept(
        kind: String,
        refId: String,
    ): Flow<Boolean>

    @Upsert
    suspend fun keep(row: KeptCollectionEntity)

    @Query("DELETE FROM kept_collection WHERE kind = :kind AND refId = :refId")
    suspend fun unkeep(
        kind: String,
        refId: String,
    )

    @Query("DELETE FROM kept_collection")
    suspend fun deleteAll()

    /** Every kept track. Room re-emits when a track, a playlist entry or a kept collection changes. */
    @Query("SELECT * FROM track WHERE $KEPT_TRACKS_WHERE")
    suspend fun keptTracks(): List<TrackEntity>

    @Query("SELECT id FROM track WHERE $KEPT_TRACKS_WHERE")
    fun observeKeptTrackIds(): Flow<List<String>>
}

private const val KEPT_TRACKS_WHERE =
    "albumId IN (SELECT refId FROM kept_collection WHERE kind = 'album') " +
        "OR id IN (SELECT pi.trackId FROM playlist_item pi " +
        "JOIN kept_collection k ON k.kind = 'playlist' AND k.refId = CAST(pi.playlistId AS TEXT))"
