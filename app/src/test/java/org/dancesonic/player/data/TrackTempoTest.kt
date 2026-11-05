package org.dancesonic.player.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.dancesonic.player.dance.NoDanceMetadataSource
import org.dancesonic.player.data.db.AlbumEntity
import org.dancesonic.player.data.db.ArtistEntity
import org.dancesonic.player.data.db.DanceLookupEntity
import org.dancesonic.player.data.db.DanceSonicDatabase
import org.dancesonic.player.data.db.TrackEntity
import org.dancesonic.player.testsupport.TestSubsonicServer
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
class TrackTempoTest {
    private lateinit var db: DanceSonicDatabase

    @Before
    fun setUp() {
        db =
            Room.inMemoryDatabaseBuilder(
                ApplicationProvider.getApplicationContext(),
                DanceSonicDatabase::class.java,
            ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `the server's tempo is shown, and DanceBrainz's only when the server has none`() =
        runBlocking(Dispatchers.IO) {
            seedTrack(id = "s-1", mbid = "rec-1", bpm = 118)
            seedTrack(id = "s-2", mbid = "rec-2", bpm = null)
            seedTrack(id = "s-3", mbid = "rec-3", bpm = null)
            seedTrack(id = "s-4", mbid = null, bpm = null)
            db.danceLookupDao().upsert(DanceLookupEntity("rec-1", checkedAt = 1L, bpm = 90, raters = 2))
            db.danceLookupDao().upsert(DanceLookupEntity("rec-2", checkedAt = 1L, bpm = 90, raters = 2))

            val tempos = db.trackDao().observeByAlbumWithTempo("al-1").first().associate { it.track.id to it.tempoBpm }

            assertEquals(118, tempos["s-1"])
            assertEquals(90, tempos["s-2"])
            assertNull(tempos["s-3"])
            assertNull(tempos["s-4"])
        }

    @Test
    fun `a song's bpm from folksonic reaches the track during a sync, and DanceBrainz fills the rest`() {
        TestSubsonicServer().use { server ->
            server.enqueueJson(ARTISTS)
            server.enqueueJson(ALBUM_LIST_ONE)
            server.enqueueJson(EMPTY_ALBUM_LIST)
            server.enqueueSongsOf(ALBUM_WITH_BPM)
            val repo = LibraryRepository(db, NoDanceMetadataSource, clock = { 1_000L })

            val stored =
                runBlocking(Dispatchers.IO) {
                    repo.sync(server.client)
                    db.trackDao().get("s-1")
                }

            assertEquals(118, stored?.bpm)
        }
    }

    private suspend fun seedTrack(
        id: String,
        mbid: String?,
        bpm: Int?,
    ) {
        db.artistDao().upsertAll(listOf(ArtistEntity(id = "ar-1", name = "Ada", syncedAt = 1L)))
        db.albumDao().upsertAll(
            listOf(AlbumEntity(id = "al-1", artistId = "ar-1", name = "Night", year = 1999, syncedAt = 1L)),
        )
        db.trackDao().upsertAll(
            listOf(
                TrackEntity(
                    id = id,
                    albumId = "al-1",
                    artistName = "Ada",
                    title = id,
                    trackNumber = null,
                    durationSec = 200,
                    mbid = mbid,
                    syncedAt = 1L,
                    bpm = bpm,
                ),
            ),
        )
    }

    private companion object {
        const val ARTISTS =
            """{"subsonic-response":{"status":"ok","artists":{"index":[{"name":"A",""" +
                """"artist":[{"id":"ar-1","name":"Ada"}]}]}}}"""

        const val ALBUM_LIST_ONE =
            """{"subsonic-response":{"status":"ok","albumList2":{"album":[{"id":"al-1","name":"Night",""" +
                """"artistId":"ar-1","artist":"Ada","year":1999,"songCount":1,"duration":200}]}}}"""

        const val EMPTY_ALBUM_LIST = """{"subsonic-response":{"status":"ok","albumList2":{"album":[]}}}"""

        const val ALBUM_WITH_BPM =
            """{"subsonic-response":{"status":"ok","album":{"id":"al-1","name":"Night","artist":"Ada",""" +
                """"artistId":"ar-1","year":1999,"songCount":1,"duration":200,"song":[""" +
                """{"id":"s-1","title":"One","album":"Night","artist":"Ada","albumId":"al-1","track":1,""" +
                """"duration":200,"bpm":118}]}}}"""
    }
}
