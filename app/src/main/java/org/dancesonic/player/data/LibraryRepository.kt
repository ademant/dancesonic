package org.dancesonic.player.data

import androidx.room.withTransaction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import org.dancesonic.player.data.db.AlbumEntity
import org.dancesonic.player.data.db.ArtistEntity
import org.dancesonic.player.data.db.DanceSonicDatabase
import org.dancesonic.player.data.db.DanceTypeCount
import org.dancesonic.player.data.db.TrackEntity
import org.dancesonic.player.dance.DanceMetadataSource
import org.dancesonic.player.subsonic.SubsonicClient

/** Outcome of one catalog sync. Counts are for display; the sync is all-or-nothing for catalog writes. */
data class SyncResult(
    val artists: Int,
    val albums: Int,
    val tracks: Int,
    val danceLookupsFailed: Int,
)

/**
 * Local-first library: the UI observes Room; [sync] refreshes Room from the Subsonic server
 * and DanceBrainz. Playback never depends on the database being fresh.
 */
class LibraryRepository(
    private val db: DanceSonicDatabase,
    private val danceSource: DanceMetadataSource,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    fun artists(): Flow<List<ArtistEntity>> = db.artistDao().observeAll()
    fun albums(artistId: String): Flow<List<AlbumEntity>> = db.albumDao().observeByArtist(artistId)
    fun tracks(albumId: String): Flow<List<TrackEntity>> = db.trackDao().observeByAlbum(albumId)
    fun tracksForDance(danceType: String): Flow<List<TrackEntity>> = db.trackDao().observeByDanceType(danceType)
    fun danceTypes(): Flow<List<DanceTypeCount>> = db.trackDao().observeDanceTypeCounts()

    suspend fun track(id: String): TrackEntity? = db.trackDao().get(id)
    suspend fun tracks(ids: List<String>): List<TrackEntity> = db.trackDao().getAll(ids)

    /**
     * Pulls artists, albums and songs from the server, then refreshes DanceBrainz metadata for
     * every recording MBID. Rows not seen in this run are removed. Network work happens before any
     * write, so a failed fetch leaves the previous catalog intact.
     */
    suspend fun sync(client: SubsonicClient): SyncResult = withContext(Dispatchers.IO) {
        val runStart = clock()

        val fetched = fetchCatalog(client, runStart)

        db.withTransaction {
            db.artistDao().upsertAll(fetched.artists)
            db.albumDao().upsertAll(fetched.albums)
            db.trackDao().upsertAll(fetched.tracks)
            db.trackDao().deleteStale(runStart)
            db.albumDao().deleteStale(runStart)
            db.artistDao().deleteStale(runStart)
        }

        val danceFailures = refreshDanceMetadata()

        SyncResult(
            artists = fetched.artists.size,
            albums = fetched.albums.size,
            tracks = fetched.tracks.size,
            danceLookupsFailed = danceFailures,
        )
    }

    private data class Fetched(
        val artists: List<ArtistEntity>,
        val albums: List<AlbumEntity>,
        val tracks: List<TrackEntity>,
    )

    private suspend fun fetchCatalog(client: SubsonicClient, runStart: Long): Fetched = coroutineScope {
        val permits = Semaphore(MAX_PARALLEL_REQUESTS)
        val artistStubs = client.artists()

        val artistsWithAlbums = artistStubs.map { stub ->
            async {
                permits.withPermit {
                    val artist = client.artist(stub.id)
                    val albums = artist.album.map { albumStub ->
                        async { permits.withPermit { client.album(albumStub.id) } }
                    }.awaitAll()
                    artist to albums
                }
            }
        }.awaitAll()

        val artists = mutableListOf<ArtistEntity>()
        val albums = mutableListOf<AlbumEntity>()
        val tracks = mutableListOf<TrackEntity>()

        for ((artist, albumDtos) in artistsWithAlbums) {
            val artistName = artist.name.orEmpty().ifBlank { "Unknown artist" }
            artists += ArtistEntity(id = artist.id, name = artistName, syncedAt = runStart)
            for (album in albumDtos) {
                albums += AlbumEntity(
                    id = album.id,
                    artistId = artist.id,
                    name = album.name.orEmpty().ifBlank { "Unknown album" },
                    year = album.year,
                    syncedAt = runStart,
                )
                for (song in album.song) {
                    tracks += TrackEntity(
                        id = song.id,
                        albumId = album.id,
                        artistName = song.artist ?: album.artist ?: artistName,
                        title = song.title.orEmpty().ifBlank { "Untitled" },
                        trackNumber = song.track,
                        durationSec = song.duration,
                        mbid = song.musicBrainzId?.takeIf { it.isNotBlank() },
                        syncedAt = runStart,
                    )
                }
            }
        }
        Fetched(artists, albums, tracks)
    }

    /** Returns the number of recordings whose DanceBrainz lookup failed. Cached data is kept on failure. */
    private suspend fun refreshDanceMetadata(): Int = coroutineScope {
        val permits = Semaphore(MAX_PARALLEL_REQUESTS)
        val now = clock()
        val mbids = db.trackDao().allMbids()
        val results = mbids.map { mbid ->
            async {
                permits.withPermit {
                    try {
                        val types = danceSource.danceTypesFor(mbid)
                        if (types != null) {
                            db.danceMetadataDao().replaceFor(mbid, types.distinct().sorted(), now)
                        }
                        false
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // Lookup failures are counted and reported; previously cached types are kept.
                        true
                    }
                }
            }
        }
        results.count { it.await() }
    }

    private companion object {
        const val MAX_PARALLEL_REQUESTS = 4
    }
}
