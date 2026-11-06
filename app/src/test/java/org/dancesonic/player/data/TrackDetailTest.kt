package org.dancesonic.player.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.dancesonic.player.dance.NoDanceMetadataSource
import org.dancesonic.player.data.db.AlbumEntity
import org.dancesonic.player.data.db.ArtistEntity
import org.dancesonic.player.data.db.DanceMetadataEntity
import org.dancesonic.player.data.db.DanceSonicDatabase
import org.dancesonic.player.data.db.TrackEntity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TrackDetailTest {
    private lateinit var db: DanceSonicDatabase
    private lateinit var repo: LibraryRepository

    @Before
    fun setUp() {
        db =
            Room.inMemoryDatabaseBuilder(
                ApplicationProvider.getApplicationContext(),
                DanceSonicDatabase::class.java,
            ).allowMainThreadQueries().build()
        repo = LibraryRepository(db, NoDanceMetadataSource)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `the detail of a stored track gives its album, length, tempo and dance types from the local database`() =
        runBlocking {
            db.artistDao().upsertAll(listOf(ArtistEntity("artist-1", "Artist", syncedAt = 1)))
            db.albumDao().upsertAll(listOf(AlbumEntity("album-1", "artist-1", "Album", 2001, syncedAt = 1)))
            db.trackDao().upsertAll(
                listOf(
                    TrackEntity(
                        id = "song-1",
                        albumId = "album-1",
                        artistName = "Artist",
                        title = "Track",
                        trackNumber = 1,
                        durationSec = 225,
                        mbid = "7f9c2a1e-0d3b-4c5a-9e8f-1a2b3c4d5e6f",
                        syncedAt = 1,
                        bpm = 118,
                    ),
                ),
            )
            db.danceMetadataDao().insertAll(
                listOf(
                    DanceMetadataEntity("7f9c2a1e-0d3b-4c5a-9e8f-1a2b3c4d5e6f", "gavotte", fetchedAt = 1),
                    DanceMetadataEntity("7f9c2a1e-0d3b-4c5a-9e8f-1a2b3c4d5e6f", "valse", fetchedAt = 1),
                ),
            )

            val detail = repo.trackDetail("song-1")

            assertEquals("Album", detail?.albumName)
            assertEquals(225, detail?.track?.durationSec)
            assertEquals(118, detail?.bpm)
            assertEquals(listOf("gavotte", "valse"), detail?.danceTypes)
        }

    @Test
    fun `a track that is not stored has no detail`() =
        runBlocking {
            assertNull(repo.trackDetail("missing"))
        }
}
