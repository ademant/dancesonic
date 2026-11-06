package org.dancesonic.player.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.dancesonic.player.data.db.AlbumEntity
import org.dancesonic.player.data.db.ArtistEntity
import org.dancesonic.player.data.db.DanceSonicDatabase
import org.dancesonic.player.data.db.KeptKind
import org.dancesonic.player.data.db.PlaylistEntity
import org.dancesonic.player.data.db.PlaylistItemEntity
import org.dancesonic.player.data.db.TrackEntity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class KeptCollectionsTest {
    private lateinit var db: DanceSonicDatabase
    private lateinit var kept: KeptCollections
    private var playlistId = 0L

    @Before
    fun setUp() {
        db =
            Room.inMemoryDatabaseBuilder(
                ApplicationProvider.getApplicationContext(),
                DanceSonicDatabase::class.java,
            ).allowMainThreadQueries().build()
        kept = KeptCollections(db, clock = { 42L })
        runBlocking {
            db.artistDao().upsertAll(listOf(ArtistEntity(id = "artist", name = "Artist", syncedAt = 1)))
            db.albumDao().upsertAll(
                listOf(
                    AlbumEntity(id = "album-a", artistId = "artist", name = "A", year = null, syncedAt = 1),
                    AlbumEntity(id = "album-b", artistId = "artist", name = "B", year = null, syncedAt = 1),
                ),
            )
            db.trackDao().upsertAll(
                listOf(
                    track("a1", "album-a"),
                    track("a2", "album-a"),
                    track("b1", "album-b"),
                    track("b2", "album-b"),
                ),
            )
            playlistId = db.playlistDao().insert(PlaylistEntity(name = "Mix", createdAt = 1, updatedAt = 1))
            db.playlistItemDao().insert(item(trackId = "b1", position = 0))
            // The server dropped this track: the playlist entry stays, but it matches no track row.
            db.playlistItemDao().insert(item(trackId = "gone", position = 1))
            db.playlistItemDao().insert(item(trackId = "a1", position = 2))
        }
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun track(
        id: String,
        albumId: String,
    ) = TrackEntity(
        id = id,
        albumId = albumId,
        artistName = "Artist",
        title = id,
        trackNumber = null,
        durationSec = 200,
        mbid = null,
        syncedAt = 1,
        sizeBytes = 3_000_000,
    )

    private fun item(
        trackId: String,
        position: Int,
    ) = PlaylistItemEntity(playlistId = playlistId, trackId = trackId, danceType = null, position = position)

    private fun keptIds(): List<String> = runBlocking { db.keptCollectionDao().keptTracks().map { it.id }.sorted() }

    @Test
    fun `nothing is kept until a collection is marked`() {
        assertEquals(emptyList<String>(), keptIds())
    }

    @Test
    fun `a kept album keeps all its tracks`() =
        runBlocking {
            kept.set(KeptKind.ALBUM, "album-a", keep = true)

            // a1 is also in the playlist, so it appears once.
            assertEquals(listOf("a1", "a2"), keptIds())
        }

    @Test
    fun `a kept playlist keeps its tracks but not the ones the server dropped`() =
        runBlocking {
            kept.set(KeptKind.PLAYLIST, playlistId.toString(), keep = true)

            assertEquals(listOf("a1", "b1"), keptIds())
        }

    @Test
    fun `unmarking a collection drops only the tracks no other kept collection covers`() =
        runBlocking {
            kept.set(KeptKind.ALBUM, "album-a", keep = true)
            kept.set(KeptKind.PLAYLIST, playlistId.toString(), keep = true)
            kept.set(KeptKind.ALBUM, "album-a", keep = false)

            assertEquals(listOf("a1", "b1"), keptIds())
        }

    @Test
    fun `forgetAll removes every choice`() =
        runBlocking {
            kept.set(KeptKind.ALBUM, "album-a", keep = true)
            kept.forgetAll()

            assertEquals(emptyList<String>(), keptIds())
        }
}
