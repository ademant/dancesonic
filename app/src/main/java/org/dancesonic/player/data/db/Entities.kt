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
    /** Tempo from the server's own library (folksonic). Null when the file has none; DanceBrainz is the fallback. */
    val bpm: Int? = null,
    /** ReplayGain track gain in dB, from the server. Null when the file has none. */
    val trackGain: Double? = null,
    /** ReplayGain track peak, linear. Null when the file has none. */
    val trackPeak: Double? = null,
    /** Size of the audio file in bytes, from the server. Null when unknown; the cache then cannot tell a full file. */
    val sizeBytes: Long? = null,
)

/** Tempos outside this range are not stored. DanceBrainz accepts exactly this range. */
internal val TEMPO_RANGE = MIN_TEMPO_BPM..MAX_TEMPO_BPM

private const val MIN_TEMPO_BPM = 1
private const val MAX_TEMPO_BPM = 400

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

/**
 * When each recording was last looked up in DanceBrainz, including lookups that found nothing, and the
 * public tempo that lookup returned. [bpm] is null when nobody has reported a tempo.
 */
@Entity(tableName = "dance_lookup")
data class DanceLookupEntity(
    @PrimaryKey val mbid: String,
    val checkedAt: Long,
    val bpm: Int? = null,
    val raters: Int? = null,
)

/**
 * The DanceBrainz shoes ratings last read for a recording: the signed-in viewer's own value ([myShoes], null when
 * unrated or signed out) and the public median. [checkedAt] is when they were read.
 */
@Entity(tableName = "shoes_rating")
data class ShoesRatingEntity(
    @PrimaryKey val mbid: String,
    val myShoes: Int?,
    val median: Double?,
    val raters: Int,
    val checkedAt: Long,
)

/**
 * A shoes change the phone has made but DanceBrainz has not confirmed. [value] is null for a cleared rating.
 * One row per recording: a newer change replaces an older one. [lastError] is the message of the last failed send.
 */
@Entity(tableName = "shoes_edit")
data class ShoesEditEntity(
    @PrimaryKey val mbid: String,
    val value: Int?,
    val queuedAt: Long,
    val lastError: String?,
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
