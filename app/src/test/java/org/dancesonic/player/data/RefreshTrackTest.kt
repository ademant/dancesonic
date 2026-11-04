package org.dancesonic.player.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.dancesonic.player.dance.NoDanceMetadataSource
import org.dancesonic.player.data.db.DanceSonicDatabase
import org.dancesonic.player.testsupport.TestSubsonicServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RefreshTrackTest {
    private lateinit var db: DanceSonicDatabase
    private lateinit var server: TestSubsonicServer
    private lateinit var repo: LibraryRepository

    @Before
    fun setUp() {
        db =
            Room.inMemoryDatabaseBuilder(
                ApplicationProvider.getApplicationContext(),
                DanceSonicDatabase::class.java,
            ).allowMainThreadQueries().build()
        server = TestSubsonicServer()
        repo = LibraryRepository(db, NoDanceMetadataSource, clock = { 1_000L })
        server.enqueueJson(ARTISTS)
        server.enqueueJson(ALBUM_LIST_ONE)
        server.enqueueJson(EMPTY_ALBUM_LIST)
        server.enqueueSongsOf(ALBUM_FULL)
        runBlocking(Dispatchers.IO) { repo.sync(server.client) }
    }

    @After
    fun tearDown() {
        server.close()
        db.close()
    }

    @Test
    fun `an unchanged track costs one request and writes nothing`() =
        runBlocking(Dispatchers.IO) {
            val before = server.server.requestCount
            server.enqueueJson(SONG)

            repo.refreshTrack(server.client, "s-1")

            assertEquals(before + 1, server.server.requestCount)
            assertEquals("One", db.trackDao().get("s-1")!!.title)
        }

    @Test
    fun `a changed title updates the stored track without refetching the album`() =
        runBlocking(Dispatchers.IO) {
            val before = server.server.requestCount
            server.enqueueJson(SONG_RETITLED)

            repo.refreshTrack(server.client, "s-1")

            assertEquals(before + 1, server.server.requestCount)
            assertEquals("Uno", db.trackDao().get("s-1")!!.title)
        }

    @Test
    fun `a renamed album is refetched and its name stored`() =
        runBlocking(Dispatchers.IO) {
            server.enqueueJson(SONG_IN_RENAMED_ALBUM)
            server.enqueueJson(ALBUM_RENAMED)

            repo.refreshTrack(server.client, "s-1")

            assertEquals("Day", db.albumDao().get("al-1")!!.name)
        }

    private companion object {
        const val ARTISTS =
            """{"subsonic-response":{"status":"ok","artists":{"index":[{"name":"A",""" +
                """"artist":[{"id":"ar-1","name":"Ada"}]}]}}}"""

        const val ALBUM_LIST_ONE =
            """{"subsonic-response":{"status":"ok","albumList2":{"album":[{"id":"al-1","name":"Night",""" +
                """"artistId":"ar-1","artist":"Ada","year":1999,"songCount":1,"duration":200}]}}}"""

        const val EMPTY_ALBUM_LIST = """{"subsonic-response":{"status":"ok","albumList2":{"album":[]}}}"""

        const val ALBUM_FULL =
            """{"subsonic-response":{"status":"ok","album":{"id":"al-1","name":"Night","artist":"Ada",""" +
                """"artistId":"ar-1","year":1999,"songCount":1,"duration":200,"song":[""" +
                """{"id":"s-1","title":"One","album":"Night","artist":"Ada","albumId":"al-1","track":1,""" +
                """"duration":200,"musicBrainzId":"rec-1"}]}}}"""

        const val SONG =
            """{"subsonic-response":{"status":"ok","song":{"id":"s-1","title":"One","album":"Night",""" +
                """"artist":"Ada","albumId":"al-1","track":1,"duration":200,"musicBrainzId":"rec-1"}}}"""

        const val SONG_RETITLED =
            """{"subsonic-response":{"status":"ok","song":{"id":"s-1","title":"Uno","album":"Night",""" +
                """"artist":"Ada","albumId":"al-1","track":1,"duration":200,"musicBrainzId":"rec-1"}}}"""

        const val SONG_IN_RENAMED_ALBUM =
            """{"subsonic-response":{"status":"ok","song":{"id":"s-1","title":"One","album":"Day",""" +
                """"artist":"Ada","albumId":"al-1","track":1,"duration":200,"musicBrainzId":"rec-1"}}}"""

        const val ALBUM_RENAMED =
            """{"subsonic-response":{"status":"ok","album":{"id":"al-1","name":"Day","artist":"Ada",""" +
                """"artistId":"ar-1","year":1999,"songCount":1,"duration":200,"song":[""" +
                """{"id":"s-1","title":"One","album":"Day","artist":"Ada","albumId":"al-1","track":1,""" +
                """"duration":200,"musicBrainzId":"rec-1"}]}}}"""
    }
}
