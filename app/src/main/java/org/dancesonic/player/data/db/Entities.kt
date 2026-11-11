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
    /** The version of the recording's chapters when last looked up; a chapter write sends it as `If-Match`. */
    val chaptersVersion: String? = null,
)

/**
 * A chapter mark of a recording, from the last lookup or read. It runs from [startMs] to [endMs] (null when
 * DanceBrainz does not know the recording's length). The dance is the tag id and name, null when not set.
 */
@Entity(
    tableName = "chapter",
    primaryKeys = ["mbid", "startMs"],
    indices = [Index("mbid")],
)
data class ChapterEntity(
    val mbid: String,
    val startMs: Long,
    val endMs: Long?,
    val title: String?,
    val danceTagId: Long?,
    val danceName: String?,
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

/** The kinds of change in [EditEntity.kind]. */
object EditKind {
    /** A shoes rating set, or cleared when [EditEntity.value] is null. */
    const val SHOES = "shoes"

    /** A dance added to a recording, at [EditEntity.path] with [EditEntity.qualifier]. */
    const val DANCE_ADD = "dance_add"

    /** A dance removed from a recording, named [EditEntity.danceName]. */
    const val DANCE_REMOVE = "dance_remove"
}

/** The states in [EditEntity.status]. */
object EditStatus {
    /** Waiting to be sent. */
    const val QUEUED = "queued"

    /** Sent, and held for review by a moderated DanceBrainz server. Not sent again. */
    const val PENDING_REVIEW = "pending_review"

    /** The track changed on DanceBrainz since the phone showed it, so nothing was sent. Not sent again. */
    const val CONFLICT = "conflict"
}

/**
 * A change the phone has made to DanceBrainz, queued until it is sent. Only the fields of its [kind] are set.
 * A shoes change replaces an older queued shoes change of the same recording. [expectedDances] is the JSON list of
 * dance names the phone showed when a removal was queued; a send checks it against DanceBrainz first.
 * [lastError] is the message of the last failed send.
 */
@Entity(
    tableName = "edit_queue",
    indices = [Index("mbid")],
)
data class EditEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val kind: String,
    val mbid: String,
    val value: Int? = null,
    val danceName: String? = null,
    val path: String? = null,
    val qualifier: String? = null,
    val expectedDances: String? = null,
    val status: String = EditStatus.QUEUED,
    val queuedAt: Long,
    val lastError: String? = null,
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

/**
 * The waveform of a song for the seek bar: [peaks] is the comma-separated 0..255 values, [source] is `server`
 * (folksonic's stored analysis) or `local` (decoded from the audio cache), and [computedAt] is epoch milliseconds.
 * Keyed by the server's song id, so it survives a re-sync. No foreign key: a sync rewrites track rows, and a waveform
 * does not depend on the row.
 */
@Entity(tableName = "waveform")
data class WaveformEntity(
    @PrimaryKey val songId: String,
    val peaks: String,
    val source: String,
    val computedAt: Long,
)
