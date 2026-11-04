package org.dancesonic.player.subsonic

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Top-level JSON envelope: every Subsonic JSON response is wrapped in `subsonic-response`. */
@Serializable
data class SubsonicEnvelope(
    @SerialName("subsonic-response") val response: SubsonicResponse,
)

@Serializable
data class SubsonicResponse(
    val status: String,
    val version: String? = null,
    val error: SubsonicErrorDto? = null,
    val openSubsonicExtensions: List<OpenSubsonicExtensionDto>? = null,
    val artists: ArtistsIndexDto? = null,
    val artist: ArtistDto? = null,
    val album: AlbumDto? = null,
    val albumList2: AlbumListDto? = null,
    val song: SongDto? = null,
    val searchResult3: SearchResult3Dto? = null,
    /** folksonic vendor extension `tinysonicLibraryInfo`. */
    val tinysonicLibraryInfo: LibraryInfoDto? = null,
    /** folksonic vendor extension `tinysonicLibraryChanges`. */
    val libraryChanges: LibraryChangesDto? = null,
)

/** Library totals and the last library change, as an ISO-8601 UTC string (second precision). */
@Serializable
data class LibraryInfoDto(
    val artists: Int = 0,
    val albums: Int = 0,
    val songs: Int = 0,
    val lastModified: String? = null,
)

/**
 * One page of the change feed. `playlists` and `starred` are per account and are not part of the
 * catalog, so they are not read. Ids only: the client fetches changed items with the usual endpoints.
 */
@Serializable
data class LibraryChangesDto(
    val full: Boolean = false,
    /** Sync point for the next request, epoch milliseconds. Same on every page of one snapshot. */
    val serverTime: Long = 0,
    val artists: List<ChangedIdDto> = emptyList(),
    val albums: List<ChangedIdDto> = emptyList(),
    val songs: List<ChangedIdDto> = emptyList(),
    val deleted: List<DeletedDto> = emptyList(),
    val nextCursor: String? = null,
)

@Serializable
data class ChangedIdDto(
    val id: String,
    /** Epoch milliseconds of the last change. */
    val updated: Long = 0,
)

@Serializable
data class DeletedDto(
    /** `artist`, `album`, `song`, `playlist` or `starred`. */
    val type: String,
    val id: String,
    val deleted: Long = 0,
)

@Serializable
data class SubsonicErrorDto(
    val code: Int,
    val message: String? = null,
    val helpUrl: String? = null,
)

@Serializable
data class OpenSubsonicExtensionDto(
    val name: String,
    val versions: List<Int> = emptyList(),
)

@Serializable
data class SearchResult3Dto(
    val song: List<SongDto> = emptyList(),
)

@Serializable
data class AlbumListDto(
    val album: List<AlbumDto> = emptyList(),
)

@Serializable
data class ArtistsIndexDto(
    val index: List<IndexDto> = emptyList(),
)

@Serializable
data class IndexDto(
    val name: String? = null,
    val artist: List<ArtistDto> = emptyList(),
)

@Serializable
data class ArtistDto(
    val id: String,
    val name: String? = null,
    val album: List<AlbumDto> = emptyList(),
)

@Serializable
data class AlbumDto(
    val id: String,
    val name: String? = null,
    val artist: String? = null,
    val artistId: String? = null,
    val year: Int? = null,
    /** Number of songs; changes when songs are added or removed. Used to skip unchanged albums. */
    val songCount: Int? = null,
    /** Total duration in seconds. */
    val duration: Int? = null,
    val song: List<SongDto> = emptyList(),
)

@Serializable
data class SongDto(
    val id: String,
    val title: String? = null,
    val album: String? = null,
    val artist: String? = null,
    val albumId: String? = null,
    val track: Int? = null,
    /** Duration in seconds. */
    val duration: Int? = null,
    /** OpenSubsonic extension: recording MusicBrainz ID. Optional; may be absent. */
    val musicBrainzId: String? = null,
)
