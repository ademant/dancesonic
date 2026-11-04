package org.dancesonic.player.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** Catalog rows mirror the Subsonic server. `syncedAt` marks the last sync run that changed the row. */

@Entity(tableName = "artist")
data class ArtistEntity(
    @PrimaryKey val id: String,
    val name: String,
    val syncedAt: Long,
)

@Entity(
    tableName = "album",
    foreignKeys = [
        ForeignKey(
            entity = ArtistEntity::class,
            parentColumns = ["id"],
            childColumns = ["artistId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("artistId")],
)
data class AlbumEntity(
    @PrimaryKey val id: String,
    val artistId: String,
    val name: String,
    val year: Int?,
    val syncedAt: Long,
)

@Entity(
    tableName = "track",
    foreignKeys = [
        ForeignKey(
            entity = AlbumEntity::class,
            parentColumns = ["id"],
            childColumns = ["albumId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("albumId"), Index("mbid")],
)
data class TrackEntity(
    @PrimaryKey val id: String,
    val albumId: String,
    val artistName: String,
    val title: String,
    val trackNumber: Int?,
    val durationSec: Int?,
    /** Recording MusicBrainz ID; the primary key for DanceBrainz matching. Null when unknown. */
    val mbid: String?,
    val syncedAt: Long,
)

/** DanceBrainz enrichment, keyed by recording MBID. One row per dance type a recording has. */
@Entity(
    tableName = "dance_metadata",
    primaryKeys = ["mbid", "danceType"],
)
data class DanceMetadataEntity(
    val mbid: String,
    val danceType: String,
    val fetchedAt: Long,
)

@Entity(tableName = "playlist")
data class PlaylistEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val createdAt: Long,
    val updatedAt: Long,
)

/** A track inside a course playlist, placed in a dance-type group (null = ungrouped). */
@Entity(
    tableName = "playlist_item",
    foreignKeys = [
        ForeignKey(
            entity = PlaylistEntity::class,
            parentColumns = ["id"],
            childColumns = ["playlistId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("playlistId")],
)
data class PlaylistItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val playlistId: Long,
    /** Not a foreign key: a track may disappear from the server, and the entry should survive. */
    val trackId: String,
    val danceType: String?,
    val position: Int,
)
