package org.dancesonic.player.data

import androidx.room.withTransaction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.dancesonic.player.dance.DanceMetadataSource
import org.dancesonic.player.data.db.AlbumEntity
import org.dancesonic.player.data.db.AlbumSongTotals
import org.dancesonic.player.data.db.ArtistEntity
import org.dancesonic.player.data.db.DanceLookupEntity
import org.dancesonic.player.data.db.DanceSonicDatabase
import org.dancesonic.player.data.db.DanceTypeCount
import org.dancesonic.player.data.db.TrackEntity
import org.dancesonic.player.subsonic.AlbumDto
import org.dancesonic.player.subsonic.DeletedDto
import org.dancesonic.player.subsonic.LibraryChangesDto
import org.dancesonic.player.subsonic.SubsonicClient
import org.dancesonic.player.subsonic.SubsonicException
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

/** How a sync obtained its data. */
enum class SyncMode {
    /** Every artist, album and song was listed and compared with the stored catalog. */
    Full,

    /** Only the items that changed on the server were fetched. */
    Changes,

    /** Nothing changed on the server since the last sync, so nothing was written. */
    Unchanged,
}

/**
 * Outcome of one catalog sync. Counts are for display. Writes are all-or-nothing: a failed fetch
 * leaves the stored catalog as it was.
 */
data class SyncResult(
    val artists: Int,
    val albums: Int,
    val tracks: Int,
    val danceLookupsFailed: Int,
    val skippedAlbums: Int,
    /** Rows inserted, updated or removed. Zero when the server catalog did not change. */
    val rowsWritten: Int,
    val mode: SyncMode,
)

/**
 * Local-first library: the UI observes Room; the sync methods refresh Room from the server and
 * DanceBrainz. Playback never depends on the database being fresh.
 *
 * With [syncState], the repository remembers the folksonic change-feed point after each sync, which
 * lets [syncIncremental] ask only for what changed. Without it, every sync is a full listing.
 */
@Suppress("TooManyFunctions") // The sync steps of one repository; splitting them would scatter the write order.
class LibraryRepository(
    private val db: DanceSonicDatabase,
    private val danceSource: DanceMetadataSource,
    private val clock: () -> Long = System::currentTimeMillis,
    private val syncState: SyncStateStore? = null,
    private val timings: SyncTimings = SyncTimings.None,
) {
    fun artists(): Flow<List<ArtistEntity>> = db.artistDao().observeAll()

    fun albums(artistId: String): Flow<List<AlbumEntity>> = db.albumDao().observeByArtist(artistId)

    fun tracks(albumId: String): Flow<List<TrackEntity>> = db.trackDao().observeByAlbum(albumId)

    fun tracksForDance(danceType: String): Flow<List<TrackEntity>> = db.trackDao().observeByDanceType(danceType)

    fun danceTypes(): Flow<List<DanceTypeCount>> = db.trackDao().observeDanceTypeCounts()

    suspend fun track(id: String): TrackEntity? = db.trackDao().get(id)

    suspend fun tracks(ids: List<String>): List<TrackEntity> = db.trackDao().getAll(ids)

    /**
     * Full listing of the server catalog, then DanceBrainz refresh. Used by the Refresh action and
     * as the fallback when an incremental sync cannot be used. Rows not seen in this run are removed.
     * The change-feed point is read before the listing starts, so a change made during it is picked
     * up by the next sync.
     */
    suspend fun sync(
        client: SubsonicClient,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): SyncResult =
        withContext(Dispatchers.IO) {
            val runStart = clock()
            val checkpoint = timed("checkpoint") { changeCheckpoint(client) }

            val fetched = timed("listing") { fetchCatalog(client, runStart, onProgress) }
            val rowsWritten = timed("write") { db.withTransaction { writeChangedRows(fetched) } }

            saveSyncPoint(client, checkpoint)
            syncState?.markFull(client.settings.baseUrl.toString(), clock())
            val danceFailures = timed("dance") { refreshDanceMetadata(db.trackDao().allMbids()) }

            SyncResult(
                artists = fetched.artists.size,
                albums = fetched.albums.size,
                tracks = fetched.tracks.size,
                danceLookupsFailed = danceFailures,
                skippedAlbums = fetched.skippedAlbums,
                rowsWritten = rowsWritten,
                mode = SyncMode.Full,
            )
        }

    /**
     * Brings the catalog up to date with the least traffic the server allows.
     *
     * - folksonic with a saved sync point: one small request when nothing changed; otherwise only the
     *   reported items are fetched.
     * - any other Subsonic server: artists and album stubs are compared with the stored catalog, and only
     *   the albums whose header, song count or total duration differ are fetched. Tag-only edits to a
     *   song are not visible this way and wait for a full Refresh.
     * - folksonic without a sync point yet: a full listing.
     */
    suspend fun syncIncremental(client: SubsonicClient): SyncResult {
        val store = syncState
        val url = client.settings.baseUrl.toString()
        val since = store?.serverTime(url)
        return when {
            !client.supports(CHANGES_EXTENSION) -> syncBySubsonicComparison(client)
            store == null || since == null -> sync(client)
            else -> syncFromFeed(client, store, url, since)
        }
    }

    /** Folksonic path: one info request when unchanged, otherwise the feed; a full listing when the feed says so. */
    private suspend fun syncFromFeed(
        client: SubsonicClient,
        store: SyncStateStore,
        url: String,
        since: Long,
    ): SyncResult =
        withContext(Dispatchers.IO) {
            if (isUnchanged(client.libraryInfo().lastModified, since)) {
                store.save(url, serverTime = null, checkedAt = clock())
                unchangedResult()
            } else {
                val feed = client.libraryChanges(since)
                if (feed.full) {
                    sync(client)
                } else {
                    applyChanges(client, feed).also {
                        store.save(url, serverTime = feed.serverTime, checkedAt = clock())
                    }
                }
            }
        }

    /** True when no full listing has run for [serverUrl] within [maxAgeMillis]. */
    suspend fun fullSyncDue(
        serverUrl: String,
        maxAgeMillis: Long,
    ): Boolean {
        val last = syncState?.lastFull(serverUrl) ?: return true
        return clock() - last >= maxAgeMillis
    }

    /**
     * Compares a track that just started playing with the server. Updates the stored track when it
     * differs, and its album when the album name differs. Costs one request when nothing differs.
     * A track not in the catalog is left alone; the catalog sync adds it.
     */
    suspend fun refreshTrack(
        client: SubsonicClient,
        songId: String,
    ) {
        withContext(Dispatchers.IO) {
            val stored = db.trackDao().get(songId) ?: return@withContext
            val song = client.song(songId)
            val albumId = song.albumId ?: return@withContext
            val now = clock()

            val storedAlbum = db.albumDao().get(albumId) ?: return@withContext
            if (storedAlbum.name != albumName(song.album)) {
                refreshAlbum(client, albumId, storedAlbum.artistId, now)
            }

            val fresh =
                TrackEntity(
                    id = songId,
                    albumId = albumId,
                    artistName = song.artist ?: stored.artistName,
                    title = song.title.orEmpty().ifBlank { "Untitled" },
                    trackNumber = song.track,
                    durationSec = song.duration,
                    mbid = song.musicBrainzId?.takeIf { it.isNotBlank() },
                    syncedAt = now,
                )
            val current = db.trackDao().get(songId) ?: stored
            if (current.copy(syncedAt = now) != fresh) {
                db.trackDao().upsertAll(listOf(fresh))
            }
            fresh.mbid?.takeIf { it != stored.mbid }?.let { refreshDanceMetadata(listOf(it)) }
        }
    }

    /** Fetches one album with all its songs and writes it, replacing its song list. */
    private suspend fun refreshAlbum(
        client: SubsonicClient,
        albumId: String,
        artistId: String,
        now: Long,
    ) {
        val parentName = db.artistDao().get(artistId)?.name ?: return
        val album = client.album(albumId).copy(artistId = artistId)
        val (albumRow, songRows) = album.toEntities(mapOf(artistId to parentName), now) ?: return
        db.withTransaction {
            writePatch(Patch(emptyList(), listOf(albumRow to songRows), emptySet(), emptySet(), emptySet()))
        }
    }

    /** True when no incremental check has run for [serverUrl] within [minAgeMillis]. */
    suspend fun incrementalCheckDue(
        serverUrl: String,
        minAgeMillis: Long,
    ): Boolean {
        val last = syncState?.lastCheck(serverUrl) ?: return true
        return clock() - last >= minAgeMillis
    }

    /**
     * Removes the synced catalog and DanceBrainz data. Playlists are kept: they belong to the device,
     * not to the server, and they survive a reconnect.
     */
    suspend fun clearCatalog() =
        withContext(Dispatchers.IO) {
            db.withTransaction {
                db.trackDao().deleteAll()
                db.albumDao().deleteAll()
                db.artistDao().deleteAll()
                db.danceMetadataDao().deleteAll()
                db.danceLookupDao().deleteAll()
            }
        }

    /** Runs [block] and reports how long it took, even when it fails. */
    private suspend fun <T> timed(
        phase: String,
        block: suspend () -> T,
    ): T {
        val started = System.nanoTime()
        try {
            return block()
        } finally {
            timings.record(phase, (System.nanoTime() - started) / NANOS_PER_MILLI)
        }
    }

    /** Everything a full listing read, ready to be compared with the store. */
    private data class Fetched(
        val artists: List<ArtistEntity>,
        val albums: List<AlbumEntity>,
        val tracks: List<TrackEntity>,
        val skippedAlbums: Int,
    )

    /** Rows to write in one transaction, plus the ids to remove. */
    private class Patch(
        val artists: List<ArtistEntity>,
        val albums: List<Pair<AlbumEntity, List<TrackEntity>>>,
        val deletedArtistIds: Set<String>,
        val deletedAlbumIds: Set<String>,
        val deletedSongIds: Set<String>,
    )

    /** The change-feed point to save after a listing, or null when there is no change feed or no store. */
    private suspend fun changeCheckpoint(client: SubsonicClient): Long? =
        if (syncState != null && client.supports(CHANGES_EXTENSION)) client.changeCheckpoint() else null

    private suspend fun saveSyncPoint(
        client: SubsonicClient,
        serverTime: Long?,
    ) {
        syncState?.save(client.settings.baseUrl.toString(), serverTime = serverTime, checkedAt = clock())
    }

    private suspend fun unchangedResult(): SyncResult =
        SyncResult(
            artists = db.artistDao().count(),
            albums = db.albumDao().count(),
            tracks = db.trackDao().count(),
            danceLookupsFailed = 0,
            skippedAlbums = 0,
            rowsWritten = 0,
            mode = SyncMode.Unchanged,
        )

    /**
     * Whether nothing changed after [since]. `lastModified` has one-second precision, so a change made
     * just after [since] can read as up to one second earlier; the margin keeps such a change from being
     * skipped. An unreadable timestamp is not treated as unchanged.
     */
    private fun isUnchanged(
        lastModified: String?,
        since: Long,
    ): Boolean {
        val lastMs =
            lastModified?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() } ?: return false
        return lastMs + LAST_MODIFIED_PRECISION_MS <= since
    }

    /**
     * Applies one change-feed answer. Changed albums and artists are fetched whole, then written with
     * their songs in one transaction. Tombstones remove songs, albums and artists. Playlist and starred
     * entries are per account and are not part of the catalog.
     *
     * A song change also marks its album in the feed, so songs are not fetched one by one.
     */
    private suspend fun applyChanges(
        client: SubsonicClient,
        feed: LibraryChangesDto,
    ): SyncResult {
        val runStart = clock()
        val deletedArtistIds = feed.deleted.idsOf("artist")
        val deletedAlbumIds = feed.deleted.idsOf("album")
        val deletedSongIds = feed.deleted.idsOf("song")
        val changedAlbumIds = feed.albums.map { it.id }.filter { it !in deletedAlbumIds }.distinct()
        val changedArtistIds = feed.artists.map { it.id }.filter { it !in deletedArtistIds }.distinct()

        val albums = fetchEach(changedAlbumIds) { client.album(it) }
        // An album needs its artist row: fetch a parent the feed did not list and the store lacks.
        val missingParentIds =
            albums.mapNotNull { it.artistId }.distinct()
                .filter { it !in changedArtistIds && db.artistDao().get(it) == null }
        val artists = fetchEach(changedArtistIds + missingParentIds) { client.artist(it) }

        val artistRows = artists.map { artistRow(it.id, it.name, runStart) }
        val artistNames = artistRows.associate { it.id to it.name }.toMutableMap()
        for (album in albums) {
            val parentId = album.artistId ?: continue
            if (parentId !in artistNames) {
                db.artistDao().get(parentId)?.let { artistNames[parentId] = it.name }
            }
        }
        val albumRows = albums.mapNotNull { it.toEntities(artistNames, runStart) }
        val patch = Patch(artistRows, albumRows, deletedArtistIds, deletedAlbumIds, deletedSongIds)
        // Albums the server no longer has (code 70) and albums without a known artist both count as skipped.
        return commitPatch(patch, skipped = changedAlbumIds.size - albumRows.size)
    }

    /**
     * Subsonic fallback without a change feed. Artists come from the index and albums from the paged
     * album lists, both cheap. An album is fetched whole only when its stored header, song count or
     * total duration differs from the listing. Removed artists and albums are deleted.
     */
    private suspend fun syncBySubsonicComparison(client: SubsonicClient): SyncResult =
        withContext(Dispatchers.IO) {
            val runStart = clock()
            val serverArtistNames = client.artists().associate { it.id to artistName(it.name) }
            val stubs = client.allAlbums()
            val storedArtists = db.artistDao().all().associateBy { it.id }
            val storedAlbums = db.albumDao().all().associateBy { it.id }
            val totals = db.trackDao().totalsByAlbum().associateBy { it.albumId }

            val artistRows =
                serverArtistNames.map { (id, name) -> artistRow(id, name, runStart) }
                    .filter { storedArtists[it.id]?.copy(syncedAt = runStart) != it }
            val stubById = stubs.associateBy { it.id }
            val changedIds =
                stubs.filter { it.differsFrom(storedAlbums[it.id], totals[it.id], serverArtistNames) }
                    .map { it.id }
            val albumRows =
                fetchEach(changedIds) { id ->
                    // The album list is the reliable source for the parent artist.
                    client.album(id).copy(artistId = stubById.getValue(id).artistId)
                }.mapNotNull { it.toEntities(serverArtistNames, runStart) }
            val patch =
                Patch(
                    artists = artistRows,
                    albums = albumRows,
                    deletedArtistIds = storedArtists.keys - serverArtistNames.keys,
                    deletedAlbumIds = storedAlbums.keys - stubs.mapTo(mutableSetOf()) { it.id },
                    deletedSongIds = emptySet(),
                )
            commitPatch(patch, skipped = stubs.count { !it.hasArtistIn(serverArtistNames) })
        }

    /** Writes [patch] in one transaction, then looks up DanceBrainz types for the songs it wrote. */
    private suspend fun commitPatch(
        patch: Patch,
        skipped: Int,
    ): SyncResult {
        val rowsWritten = db.withTransaction { writePatch(patch) }
        val songs = patch.albums.flatMap { it.second }
        val danceFailures = refreshDanceMetadata(songs.mapNotNull { it.mbid }.distinct())
        return SyncResult(
            artists = patch.artists.size,
            albums = patch.albums.size,
            tracks = songs.size,
            danceLookupsFailed = danceFailures,
            skippedAlbums = skipped,
            rowsWritten = rowsWritten,
            mode = if (rowsWritten == 0) SyncMode.Unchanged else SyncMode.Changes,
        )
    }

    /** Inside the caller's transaction. Returns the number of rows written or removed. */
    private suspend fun writePatch(patch: Patch): Int {
        val artistDao = db.artistDao()
        val albumDao = db.albumDao()
        val trackDao = db.trackDao()
        // Children before parents for deletes, parents before children for inserts.
        patch.deletedSongIds.chunked(WRITE_BATCH_SIZE).forEach { trackDao.deleteByIds(it) }
        patch.deletedAlbumIds.chunked(WRITE_BATCH_SIZE).forEach { albumDao.deleteByIds(it) }
        patch.deletedArtistIds.chunked(WRITE_BATCH_SIZE).forEach { artistDao.deleteByIds(it) }
        patch.artists.chunked(WRITE_BATCH_SIZE).forEach { artistDao.upsertAll(it) }
        var written = 0
        for ((albumRow, songRows) in patch.albums) {
            if (albumDao.get(albumRow.id)?.copy(syncedAt = albumRow.syncedAt) != albumRow) {
                albumDao.upsertAll(listOf(albumRow))
                written++
            }
            // Only songs that differ are written; songs the server no longer lists for this album are removed.
            val stored = trackDao.forAlbum(albumRow.id).associateBy { it.id }
            val changed = songRows.filter { stored[it.id]?.copy(syncedAt = it.syncedAt) != it }
            val removed = stored.keys - songRows.mapTo(mutableSetOf()) { it.id }
            removed.toList().chunked(WRITE_BATCH_SIZE).forEach { trackDao.deleteByIds(it) }
            changed.chunked(WRITE_BATCH_SIZE).forEach { trackDao.upsertAll(it) }
            written += changed.size + removed.size
        }
        return patch.deletedSongIds.size + patch.deletedAlbumIds.size + patch.deletedArtistIds.size +
            patch.artists.size + written
    }

    /**
     * Writes only what differs from the stored catalog, inside the caller's transaction. A row is
     * unchanged when everything except [AlbumEntity.syncedAt] matches, so unchanged rows are not written
     * at all. Rows missing from the server are removed by id. Returns the number of rows written or removed.
     */
    private suspend fun writeChangedRows(fetched: Fetched): Int {
        val artistDao = db.artistDao()
        val albumDao = db.albumDao()
        val trackDao = db.trackDao()

        val storedArtists = artistDao.all().associateBy { it.id }
        val storedAlbums = albumDao.all().associateBy { it.id }
        val storedTracks = trackDao.all().associateBy { it.id }

        val changedArtists = fetched.artists.filter { storedArtists[it.id]?.copy(syncedAt = it.syncedAt) != it }
        val changedAlbums = fetched.albums.filter { storedAlbums[it.id]?.copy(syncedAt = it.syncedAt) != it }
        val changedTracks = fetched.tracks.filter { storedTracks[it.id]?.copy(syncedAt = it.syncedAt) != it }

        val staleTrackIds = storedTracks.keys - fetched.tracks.mapTo(mutableSetOf()) { it.id }
        val staleAlbumIds = storedAlbums.keys - fetched.albums.mapTo(mutableSetOf()) { it.id }
        val staleArtistIds = storedArtists.keys - fetched.artists.mapTo(mutableSetOf()) { it.id }

        // Parents before children for inserts; children before parents for deletes.
        changedArtists.chunked(WRITE_BATCH_SIZE).forEach { artistDao.upsertAll(it) }
        changedAlbums.chunked(WRITE_BATCH_SIZE).forEach { albumDao.upsertAll(it) }
        changedTracks.chunked(WRITE_BATCH_SIZE).forEach { trackDao.upsertAll(it) }
        staleTrackIds.toList().chunked(WRITE_BATCH_SIZE).forEach { trackDao.deleteByIds(it) }
        staleAlbumIds.toList().chunked(WRITE_BATCH_SIZE).forEach { albumDao.deleteByIds(it) }
        staleArtistIds.toList().chunked(WRITE_BATCH_SIZE).forEach { artistDao.deleteByIds(it) }

        return changedArtists.size + changedAlbums.size + changedTracks.size +
            staleTrackIds.size + staleAlbumIds.size + staleArtistIds.size
    }

    /**
     * Artists from the index, albums from paged album lists, then one getAlbum per album for its
     * songs. Albums whose artist is not in the index are skipped and counted, because a row must
     * have a parent to satisfy the foreign key.
     */
    private suspend fun fetchCatalog(
        client: SubsonicClient,
        runStart: Long,
        onProgress: (done: Int, total: Int) -> Unit,
    ): Fetched =
        coroutineScope {
            val permits = Semaphore(MAX_PARALLEL_REQUESTS)
            val artistNames = timed("index") { client.artists() }.associate { it.id to artistName(it.name) }
            val albumStubs = timed("album-list") { client.allAlbums() }

            val known = albumStubs.filter { it.hasArtistIn(artistNames) }
            val done = AtomicInteger(0)
            onProgress(0, known.size)
            val fullAlbums =
                known.map { stub ->
                    async {
                        // The album list is the reliable source for the parent artist.
                        val album =
                            permits.withPermit { timed("album-request") { client.album(stub.id) } }
                                .copy(artistId = stub.artistId)
                        onProgress(done.incrementAndGet(), known.size)
                        album
                    }
                }.awaitAll()

            val artists = artistNames.map { (id, name) -> artistRow(id, name, runStart) }
            val albums = mutableListOf<AlbumEntity>()
            val tracks = mutableListOf<TrackEntity>()
            for (album in fullAlbums) {
                val (albumRow, songRows) = album.toEntities(artistNames, runStart) ?: continue
                albums += albumRow
                tracks += songRows
            }
            Fetched(artists, albums, tracks, skippedAlbums = albumStubs.size - albums.size)
        }

    /**
     * Whether this album, as listed, differs from the stored album: its header, or the song count or
     * total duration of its stored songs. Decides which albums need a full fetch.
     */
    private fun AlbumDto.differsFrom(
        stored: AlbumEntity?,
        totals: AlbumSongTotals?,
        artistNames: Map<String, String>,
    ): Boolean {
        if (!hasArtistIn(artistNames)) return false
        if (stored == null || headerDiffers(stored)) return true
        val storedSongs = totals?.songCount ?: 0
        val storedSeconds = totals?.duration ?: 0L
        val countDiffers = songCount != null && songCount != storedSongs
        val durationDiffers = duration != null && duration.toLong() != storedSeconds
        return countDiffers || durationDiffers
    }

    private fun AlbumDto.headerDiffers(stored: AlbumEntity): Boolean =
        stored.artistId != artistId || stored.name != albumName(name) || stored.year != year

    /**
     * Rows for one album and its songs. Null when the album's artist is not in [artistNames]. A song
     * without its own artist takes the album's, then the parent's.
     */
    private fun AlbumDto.toEntities(
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
        val songs =
            song.map {
                TrackEntity(
                    id = it.id,
                    albumId = id,
                    artistName = it.artist ?: artist ?: parentName,
                    title = it.title.orEmpty().ifBlank { "Untitled" },
                    trackNumber = it.track,
                    durationSec = it.duration,
                    mbid = it.musicBrainzId?.takeIf { mbid -> mbid.isNotBlank() },
                    syncedAt = runStart,
                )
            }
        return album to songs
    }

    /** Fetches each id with at most [MAX_PARALLEL_REQUESTS] in flight. Ids the server no longer has are left out. */
    private suspend fun <T : Any> fetchEach(
        ids: List<String>,
        fetch: suspend (String) -> T,
    ): List<T> =
        coroutineScope {
            val permits = Semaphore(MAX_PARALLEL_REQUESTS)
            ids.map { id -> async { permits.withPermit { fetchOrNull { fetch(id) } } } }.awaitAll().filterNotNull()
        }

    /**
     * Looks up DanceBrainz types for the recordings in [mbids] that were not checked within
     * [DANCE_MAX_AGE_MILLIS]. A recording with no information is recorded too, so it is not asked again
     * every sync. A failed lookup records nothing and is retried next time. Returns the number of
     * failed lookups; previously cached types are kept.
     */
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private suspend fun refreshDanceMetadata(mbids: List<String>): Int =
        coroutineScope {
            val now = clock()
            val fresh =
                mbids.chunked(WRITE_BATCH_SIZE).flatMapTo(mutableSetOf()) {
                    db.danceLookupDao().checkedSince(it, since = now - DANCE_MAX_AGE_MILLIS)
                }
            val due = mbids.distinct().filter { it !in fresh }
            val permits = Semaphore(MAX_PARALLEL_REQUESTS)
            val results =
                due.map { mbid ->
                    async {
                        permits.withPermit {
                            try {
                                val types = danceSource.danceTypesFor(mbid)
                                db.withTransaction {
                                    if (types != null) {
                                        db.danceMetadataDao().replaceFor(mbid, types.distinct().sorted(), now)
                                    }
                                    db.danceLookupDao().upsert(DanceLookupEntity(mbid, checkedAt = now))
                                }
                                false
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                // Counted and reported; nothing is recorded, so the next sync retries it.
                                true
                            }
                        }
                    }
                }
            results.count { it.await() }
        }

    /** Null when the server no longer has the item: it changed and was removed within one snapshot. */
    private suspend fun <T> fetchOrNull(block: suspend () -> T): T? =
        try {
            block()
        } catch (e: SubsonicException) {
            if (e.code == NOT_FOUND_CODE) null else throw e
        }

    private companion object {
        const val MAX_PARALLEL_REQUESTS = 4

        /** Rows per insert or delete statement; keeps each statement and its bind list small. */
        const val WRITE_BATCH_SIZE = 500

        /** Extension name of the folksonic change feed. */
        const val CHANGES_EXTENSION = "tinysonicLibraryChanges"

        /** Subsonic error code for "data not found". */
        const val NOT_FOUND_CODE = 70

        const val NANOS_PER_MILLI = 1_000_000L

        /** A recording is looked up again once its last lookup is this old. */
        const val DANCE_MAX_AGE_MILLIS = 30L * 24 * 60 * 60 * 1000

        /** `lastModified` is truncated to whole seconds. */
        const val LAST_MODIFIED_PRECISION_MS = 1_000L
    }
}

private fun List<DeletedDto>.idsOf(type: String): Set<String> =
    filter { it.type == type }.mapTo(mutableSetOf()) { it.id }

private fun AlbumDto.hasArtistIn(artistNames: Map<String, String>): Boolean =
    artistId?.let { it in artistNames } == true

private fun artistName(raw: String?): String = raw.orEmpty().ifBlank { "Unknown artist" }

private fun albumName(raw: String?): String = raw.orEmpty().ifBlank { "Unknown album" }

private fun artistRow(
    id: String,
    name: String?,
    syncedAt: Long,
): ArtistEntity = ArtistEntity(id = id, name = artistName(name), syncedAt = syncedAt)
