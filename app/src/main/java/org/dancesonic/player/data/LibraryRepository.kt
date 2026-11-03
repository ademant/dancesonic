package org.dancesonic.player.data

import androidx.room.withTransaction
import java.util.concurrent.atomic.AtomicInteger
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
    val skippedAlbums: Int,
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
    suspend fun sync(
        client: SubsonicClient,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): SyncResult = withContext(Dispatchers.IO) {
        val runStart = clock()

        val fetched = fetchCatalog(client, runStart, onProgress)

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
            skippedAlbums = fetched.skippedAlbums,
        )
    }

    /**
     * Removes the synced catalog and DanceBrainz data. Playlists are kept: they belong to the device,
     * not to the server, and they survive a reconnect.
     */
    suspend fun clearCatalog() = withContext(Dispatchers.IO) {
        db.withTransaction {
            db.trackDao().deleteAll()
            db.albumDao().deleteAll()
            db.artistDao().deleteAll()
            db.danceMetadataDao().deleteAll()
        }
    }

    private data class Fetched(
        val artists: List<ArtistEntity>,
        val albums: List<AlbumEntity>,
        val tracks: List<TrackEntity>,
        val skippedAlbums: Int,
    )

    /**
     * Artists from the index, albums from paged album lists, then one getAlbum per album for its
     * songs. Albums whose artist is not in the index are skipped and counted, because a row must
     * have a parent to satisfy the foreign key.
     */
    private suspend fun fetchCatalog(
        client: SubsonicClient,
        runStart: Long,
        onProgress: (done: Int, total: Int) -> Unit,
    ): Fetched = coroutineScope {
        val permits = Semaphore(MAX_PARALLEL_REQUESTS)
        val artistDtos = client.artists()
        val artistNames = artistDtos.associate { it.id to it.name.orEmpty().ifBlank { "Unknown artist" } }
        val albumStubs = client.allAlbums()

        val known = albumStubs.filter { it.artistId != null && it.artistId in artistNames }
        val done = AtomicInteger(0)
        onProgress(0, known.size)
        val fullAlbums = known.map { stub ->
            async {
                val album = permits.withPermit { client.album(stub.id) }
                onProgress(done.incrementAndGet(), known.size)
                stub to album
            }
        }.awaitAll()

        val artists = artistNames.map { (id, name) -> ArtistEntity(id = id, name = name, syncedAt = runStart) }
        val albums = mutableListOf<AlbumEntity>()
        val tracks = mutableListOf<TrackEntity>()
        for ((stub, album) in fullAlbums) {
            // The album list is the reliable source for the parent artist.
            val artistId = checkNotNull(stub.artistId)
            val artistName = artistNames.getValue(artistId)
            albums += AlbumEntity(
                id = album.id,
                artistId = artistId,
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
        Fetched(artists, albums, tracks, skippedAlbums = albumStubs.size - known.size)
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
