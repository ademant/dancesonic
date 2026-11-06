package org.dancesonic.player.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface ArtistDao {
    @Query("SELECT * FROM artist ORDER BY name COLLATE NOCASE")
    fun observeAll(): Flow<List<ArtistEntity>>

    @Query("SELECT * FROM artist WHERE id = :id")
    suspend fun get(id: String): ArtistEntity?

    @Upsert
    suspend fun upsertAll(artists: List<ArtistEntity>)

    @Query("DELETE FROM artist")
    suspend fun deleteAll()

    /** Every stored artist, read once per sync so unchanged rows can be skipped without a write. */
    @Query("SELECT * FROM artist")
    suspend fun all(): List<ArtistEntity>

    @Query("SELECT COUNT(*) FROM artist")
    suspend fun count(): Int

    @Query("DELETE FROM artist WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<String>)
}

@Dao
interface AlbumDao {
    @Query("SELECT * FROM album WHERE artistId = :artistId ORDER BY year, name COLLATE NOCASE")
    fun observeByArtist(artistId: String): Flow<List<AlbumEntity>>

    @Query("SELECT * FROM album WHERE id = :id")
    suspend fun get(id: String): AlbumEntity?

    @Upsert
    suspend fun upsertAll(albums: List<AlbumEntity>)

    @Query("DELETE FROM album")
    suspend fun deleteAll()

    @Query("SELECT * FROM album")
    suspend fun all(): List<AlbumEntity>

    @Query("SELECT COUNT(*) FROM album")
    suspend fun count(): Int

    @Query("DELETE FROM album WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<String>)
}

@Suppress("TooManyFunctions") // One method per query; a DAO is a list of queries.
@Dao
interface TrackDao {
    @Query("SELECT * FROM track WHERE albumId = :albumId ORDER BY trackNumber, title COLLATE NOCASE")
    fun observeByAlbum(albumId: String): Flow<List<TrackEntity>>

    @Query("SELECT * FROM track WHERE id = :id")
    suspend fun get(id: String): TrackEntity?

    @Query("SELECT * FROM track WHERE id IN (:ids)")
    suspend fun getAll(ids: List<String>): List<TrackEntity>

    /** Tracks that have at least one DanceBrainz dance type, filtered by [danceType]. */
    @Query(
        """
        SELECT t.* FROM track t
        INNER JOIN dance_metadata d ON d.mbid = t.mbid
        WHERE d.danceType = :danceType
        ORDER BY t.artistName COLLATE NOCASE, t.title COLLATE NOCASE
        """,
    )
    fun observeByDanceType(danceType: String): Flow<List<TrackEntity>>

    /** Dance types present in the library, with the number of matching tracks. */
    @Query(
        """
        SELECT d.danceType AS danceType, COUNT(DISTINCT t.id) AS trackCount
        FROM dance_metadata d
        INNER JOIN track t ON t.mbid = d.mbid
        GROUP BY d.danceType
        ORDER BY d.danceType COLLATE NOCASE
        """,
    )
    fun observeDanceTypeCounts(): Flow<List<DanceTypeCount>>

    /** The tracks of an album with their tempo. Folksonic's tempo wins; DanceBrainz's is the fallback. */
    @Query(
        """
        SELECT t.*, COALESCE(t.bpm, l.bpm) AS tempoBpm
        FROM track t
        LEFT JOIN dance_lookup l ON l.mbid = t.mbid
        WHERE t.albumId = :albumId
        ORDER BY t.trackNumber, t.title COLLATE NOCASE
        """,
    )
    fun observeByAlbumWithTempo(albumId: String): Flow<List<TrackWithTempo>>

    /** The given tracks with their tempo, for playing a playlist. */
    @Query(
        """
        SELECT t.*, COALESCE(t.bpm, l.bpm) AS tempoBpm
        FROM track t
        LEFT JOIN dance_lookup l ON l.mbid = t.mbid
        WHERE t.id IN (:ids)
        """,
    )
    suspend fun getAllWithTempo(ids: List<String>): List<TrackWithTempo>

    /** Tracks of one dance type with their tempo, as [observeByAlbumWithTempo]. */
    @Query(
        """
        SELECT t.*, COALESCE(t.bpm, l.bpm) AS tempoBpm
        FROM track t
        INNER JOIN dance_metadata d ON d.mbid = t.mbid
        LEFT JOIN dance_lookup l ON l.mbid = t.mbid
        WHERE d.danceType = :danceType
        ORDER BY t.artistName COLLATE NOCASE, t.title COLLATE NOCASE
        """,
    )
    fun observeByDanceTypeWithTempo(danceType: String): Flow<List<TrackWithTempo>>

    @Query("SELECT DISTINCT mbid FROM track WHERE mbid IS NOT NULL")
    suspend fun allMbids(): List<String>

    @Upsert
    suspend fun upsertAll(tracks: List<TrackEntity>)

    @Query("DELETE FROM track")
    suspend fun deleteAll()

    @Query("SELECT * FROM track")
    suspend fun all(): List<TrackEntity>

    @Query("SELECT COUNT(*) FROM track")
    suspend fun count(): Int

    @Query("DELETE FROM track WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<String>)

    /** The stored songs of one album, compared with its fresh song list so only changed songs are written. */
    @Query("SELECT * FROM track WHERE albumId = :albumId")
    suspend fun forAlbum(albumId: String): List<TrackEntity>

    /** Song count and total duration per album, compared with the album listing to spot changes. */
    @Query(
        """
        SELECT albumId AS albumId, COUNT(*) AS songCount, COALESCE(SUM(durationSec), 0) AS duration
        FROM track GROUP BY albumId
        """,
    )
    suspend fun totalsByAlbum(): List<AlbumSongTotals>
}

/** A track with the tempo to show: folksonic's, or DanceBrainz's when folksonic has none. Null when neither has one. */
data class TrackWithTempo(
    @Embedded val track: TrackEntity,
    val tempoBpm: Int?,
)

/** Stored song count and summed song duration (seconds) of one album. */
data class AlbumSongTotals(
    val albumId: String,
    val songCount: Int,
    val duration: Long,
)

data class DanceTypeCount(
    val danceType: String,
    val trackCount: Int,
)

@Dao
interface DanceMetadataDao {
    @Query("SELECT danceType FROM dance_metadata WHERE mbid = :mbid ORDER BY danceType")
    suspend fun danceTypesFor(mbid: String): List<String>

    @Query("SELECT danceType FROM dance_metadata WHERE mbid = :mbid ORDER BY danceType")
    fun observeDanceTypesFor(mbid: String): Flow<List<String>>

    /** Replaces all dance types of one recording with the freshly fetched set. */
    @Transaction
    suspend fun replaceFor(
        mbid: String,
        danceTypes: List<String>,
        fetchedAt: Long,
    ) {
        deleteFor(mbid)
        insertAll(danceTypes.map { DanceMetadataEntity(mbid, it, fetchedAt) })
    }

    @Query("DELETE FROM dance_metadata")
    suspend fun deleteAll()

    @Query("DELETE FROM dance_metadata WHERE mbid = :mbid")
    suspend fun deleteFor(mbid: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(rows: List<DanceMetadataEntity>)
}

@Dao
interface DanceLookupDao {
    /** The MBIDs among [mbids] that were looked up at or after [since]. */
    @Query("SELECT mbid FROM dance_lookup WHERE mbid IN (:mbids) AND checkedAt >= :since")
    suspend fun checkedSince(
        mbids: List<String>,
        since: Long,
    ): List<String>

    @Upsert
    suspend fun upsert(row: DanceLookupEntity)

    /** The stored tempo of one recording, or null when none was reported or it was never looked up. */
    @Query("SELECT bpm FROM dance_lookup WHERE mbid = :mbid")
    suspend fun bpmFor(mbid: String): Int?

    @Query("DELETE FROM dance_lookup")
    suspend fun deleteAll()
}

@Dao
interface PlaylistDao {
    @Query("SELECT * FROM playlist ORDER BY name COLLATE NOCASE")
    fun observeAll(): Flow<List<PlaylistEntity>>

    @Query("SELECT * FROM playlist WHERE id = :id")
    fun observe(id: Long): Flow<PlaylistEntity?>

    @Query("SELECT * FROM playlist WHERE id = :id")
    suspend fun get(id: Long): PlaylistEntity?

    @Insert
    suspend fun insert(playlist: PlaylistEntity): Long

    @Update
    suspend fun update(playlist: PlaylistEntity)

    @Delete
    suspend fun delete(playlist: PlaylistEntity)
}

/** A playlist entry joined with its track. [title] is null when the track has left the catalog. */
data class PlaylistItemRow(
    val id: Long,
    val playlistId: Long,
    val trackId: String,
    val danceType: String?,
    val position: Int,
    val title: String?,
    val artistName: String?,
)

@Dao
interface PlaylistItemDao {
    @Query(
        """
        SELECT pi.id AS id, pi.playlistId AS playlistId, pi.trackId AS trackId,
               pi.danceType AS danceType, pi.position AS position,
               t.title AS title, t.artistName AS artistName
        FROM playlist_item pi
        LEFT JOIN track t ON t.id = pi.trackId
        WHERE pi.playlistId = :playlistId
        ORDER BY pi.position
        """,
    )
    fun observeItems(playlistId: Long): Flow<List<PlaylistItemRow>>

    @Query("SELECT trackId FROM playlist_item WHERE playlistId = :playlistId ORDER BY position")
    suspend fun orderedTrackIds(playlistId: Long): List<String>

    @Query("SELECT * FROM playlist_item WHERE id = :id")
    suspend fun get(id: Long): PlaylistItemEntity?

    @Query("SELECT COALESCE(MAX(position), -1) FROM playlist_item WHERE playlistId = :playlistId")
    suspend fun maxPosition(playlistId: Long): Int

    @Insert
    suspend fun insert(item: PlaylistItemEntity): Long

    @Query("DELETE FROM playlist_item WHERE id = :id")
    suspend fun delete(id: Long)

    @Update
    suspend fun update(item: PlaylistItemEntity)
}
