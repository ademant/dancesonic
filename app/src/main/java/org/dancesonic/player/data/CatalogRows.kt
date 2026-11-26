package org.dancesonic.player.data

import org.dancesonic.player.data.db.AlbumEntity
import org.dancesonic.player.data.db.ArtistEntity
import org.dancesonic.player.data.db.DanceSonicDatabase
import org.dancesonic.player.data.db.TEMPO_RANGE
import org.dancesonic.player.data.db.TrackEntity
import org.dancesonic.player.subsonic.AlbumDto
import org.dancesonic.player.subsonic.LibraryChangesDto
import org.dancesonic.player.subsonic.SongDto

// Catalog rows built from what the server listed, and the writes of a change feed with items. None of this needs the
// repository's state, so it lives outside LibraryRepository.

/** Rows per write and per IN list: under SQLite's limit on bound parameters, also on Android 10. */
private const val ROW_BATCH_SIZE = 500

/** Everything a full listing read, ready to be compared with the store. */
internal data class Fetched(
    val artists: List<ArtistEntity>,
    val albums: List<AlbumEntity>,
    val tracks: List<TrackEntity>,
    val skippedAlbums: Int,
)

/**
 * The whole library from a full change feed with items (#154): artists, albums without songs, and songs, joined
 * as the listing sync joins its listings. Entries without an item were deleted after the snapshot and are left out.
 */
internal fun catalogFromFeed(
    feed: LibraryChangesDto,
    runStart: Long,
): Fetched {
    val artistNames = feed.artists.mapNotNull { it.item }.associate { it.id to artistName(it.name) }
    return assembleCatalog(
        artistNames,
        feed.albums.mapNotNull { it.item },
        feed.songs.mapNotNull { it.item },
        runStart,
    )
}

/**
 * Rows from listed artists, album stubs and songs: songs are joined to their album by id. Albums whose artist is
 * not listed are skipped and counted, because a row must have a parent; songs of unknown albums are dropped.
 */
internal fun assembleCatalog(
    artistNames: Map<String, String>,
    albumStubs: List<AlbumDto>,
    songs: List<SongDto>,
    runStart: Long,
): Fetched {
    val songsByAlbum =
        songs.mapNotNull { song -> song.albumId?.let { it to song } }.groupBy(
            { it.first },
            { it.second },
        )
    val artists = artistNames.map { (id, name) -> artistRow(id, name, runStart) }
    val albums = mutableListOf<AlbumEntity>()
    val tracks = mutableListOf<TrackEntity>()
    for (stub in albumStubs) {
        val withSongs = stub.copy(song = songsByAlbum[stub.id].orEmpty())
        val (albumRow, songRows) = withSongs.toEntities(artistNames, runStart) ?: continue
        albums += albumRow
        tracks += songRows
    }
    return Fetched(artists, albums, tracks, skippedAlbums = albumStubs.size - albums.size)
}

/**
 * Rows for one album and its songs. Null when the album's artist is not in [artistNames]. A song
 * without its own artist takes the album's, then the parent's.
 */
internal fun AlbumDto.toEntities(
    artistNames: Map<String, String>,
    runStart: Long,
): Pair<AlbumEntity, List<TrackEntity>>? {
    val parentId = artistId?.takeIf { it in artistNames } ?: return null
    val parentName = artistNames.getValue(parentId)
    val album =
        AlbumEntity(
            id = id,
            artistId = parentId,
            name = albumName(name),
            year = year,
            syncedAt = runStart,
        )
    val songs = song.map { it.toTrack(albumId = id, fallbackArtist = artist ?: parentName, runStart) }
    return album to songs
}

/** The row of one song in album [albumId]; [fallbackArtist] names it when the song names no artist of its own. */
internal fun SongDto.toTrack(
    albumId: String,
    fallbackArtist: String,
    runStart: Long,
): TrackEntity =
    TrackEntity(
        id = id,
        albumId = albumId,
        artistName = artist ?: fallbackArtist,
        title = title.orEmpty().ifBlank { "Untitled" },
        trackNumber = track,
        durationSec = duration,
        mbid = musicBrainzId?.takeIf { it.isNotBlank() },
        syncedAt = runStart,
        bpm = bpm?.takeIf { it in TEMPO_RANGE },
        trackGain = replayGain?.trackGain,
        trackPeak = replayGain?.trackPeak,
        sizeBytes = size?.takeIf { it > 0 },
    )

/**
 * Rows for changed [songs]. A song's album comes from this feed or the store; a song whose album is in neither is
 * left out, because its row needs the parent. A song naming no artist takes its album's, then the album artist's.
 */
internal suspend fun DanceSonicDatabase.songRowsOf(
    songs: List<SongDto>,
    albumItems: List<AlbumDto>,
    albumRows: List<AlbumEntity>,
    runStart: Long,
): List<TrackEntity> {
    val albumArtistNames = albumItems.associate { it.id to it.artist }
    val albumsById = albumRows.associateBy { it.id }
    return songs.mapNotNull { song ->
        val albumId = song.albumId ?: return@mapNotNull null
        val album = albumsById[albumId] ?: albumDao().get(albumId) ?: return@mapNotNull null
        val fallback =
            albumArtistNames[albumId] ?: artistDao().get(album.artistId)?.name ?: artistName(null)
        song.toTrack(albumId, fallback, runStart)
    }
}

/** Inside the caller's transaction. Writes only the rows that differ; returns how many were written or removed. */
@Suppress("LongParameterList") // The three kinds of rows and the three kinds of deletions of one feed.
internal suspend fun DanceSonicDatabase.writeItemRows(
    artists: List<ArtistEntity>,
    albums: List<AlbumEntity>,
    tracks: List<TrackEntity>,
    deletedArtistIds: Set<String>,
    deletedAlbumIds: Set<String>,
    deletedSongIds: Set<String>,
): Int {
    val artistDao = artistDao()
    val albumDao = albumDao()
    val trackDao = trackDao()
    // Children before parents for deletes, parents before children for inserts.
    deletedSongIds.chunked(ROW_BATCH_SIZE).forEach { trackDao.deleteByIds(it) }
    deletedAlbumIds.chunked(ROW_BATCH_SIZE).forEach { albumDao.deleteByIds(it) }
    deletedArtistIds.chunked(ROW_BATCH_SIZE).forEach { artistDao.deleteByIds(it) }
    val changedArtists = artists.filter { artistDao.get(it.id)?.copy(syncedAt = it.syncedAt) != it }
    val changedAlbums = albums.filter { albumDao.get(it.id)?.copy(syncedAt = it.syncedAt) != it }
    // In batches: one IN list per batch stays under SQLite's limit on bound parameters.
    val storedTracks =
        tracks
            .map { it.id }
            .chunked(ROW_BATCH_SIZE)
            .flatMap { trackDao.getAll(it) }
            .associateBy { it.id }
    val changedTracks = tracks.filter { storedTracks[it.id]?.copy(syncedAt = it.syncedAt) != it }
    changedArtists.chunked(ROW_BATCH_SIZE).forEach { artistDao.upsertAll(it) }
    changedAlbums.chunked(ROW_BATCH_SIZE).forEach { albumDao.upsertAll(it) }
    changedTracks.chunked(ROW_BATCH_SIZE).forEach { trackDao.upsertAll(it) }
    return deletedSongIds.size + deletedAlbumIds.size + deletedArtistIds.size +
        changedArtists.size + changedAlbums.size + changedTracks.size
}

internal fun artistName(raw: String?): String = raw.orEmpty().ifBlank { "Unknown artist" }

internal fun albumName(raw: String?): String = raw.orEmpty().ifBlank { "Unknown album" }

internal fun artistRow(
    id: String,
    name: String?,
    syncedAt: Long,
): ArtistEntity = ArtistEntity(id = id, name = artistName(name), syncedAt = syncedAt)
