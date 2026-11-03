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
