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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SyncTimingsTest {
    private lateinit var db: DanceSonicDatabase
    private lateinit var server: TestSubsonicServer

    @Before
    fun setUp() {
        db =
            Room.inMemoryDatabaseBuilder(
                ApplicationProvider.getApplicationContext(),
                DanceSonicDatabase::class.java,
            ).allowMainThreadQueries().build()
        server = TestSubsonicServer()
    }

    @After
    fun tearDown() {
        server.close()
        db.close()
    }

    @Test
    fun `a full sync reports each phase and reads songs in one listing, not one request per album`() =
        runBlocking(Dispatchers.IO) {
            server.enqueueJson(ARTISTS)
            server.enqueueJson(ALBUM_LIST_ONE)
            server.enqueueJson(EMPTY_ALBUM_LIST)
            server.enqueueSongsOf(ALBUM_WITH_SONG)
            val recorded = mutableListOf<Pair<String, Long>>()

            LibraryRepository(
                db,
                NoDanceMetadataSource,
                clock = { 1_000L },
                timings = { phase, millis -> synchronized(recorded) { recorded += phase to millis } },
            ).sync(server.client)

            val phases = recorded.map { it.first }
            assertEquals(0, phases.count { it == "album-request" })
            for (phase in listOf("checkpoint", "index", "album-list", "song-list", "listing", "write", "dance")) {
                assertTrue("missing $phase in $phases", phase in phases)
            }
            assertTrue(recorded.all { it.second >= 0L })
        }

    private companion object {
        const val ARTISTS =
            """{"subsonic-response":{"status":"ok","artists":{"index":[{"name":"A",""" +
                """"artist":[{"id":"ar-1","name":"Ada"}]}]}}}"""

        const val ALBUM_LIST_ONE =
            """{"subsonic-response":{"status":"ok","albumList2":{"album":[{"id":"al-1","name":"Night",""" +
                """"artistId":"ar-1","artist":"Ada","year":1999,"songCount":1,"duration":200}]}}}"""

        const val EMPTY_ALBUM_LIST = """{"subsonic-response":{"status":"ok","albumList2":{"album":[]}}}"""

        const val ALBUM_WITH_SONG =
            """{"subsonic-response":{"status":"ok","album":{"id":"al-1","name":"Night","artist":"Ada",""" +
                """"artistId":"ar-1","year":1999,"songCount":1,"duration":200,"song":[""" +
                """{"id":"s-1","title":"One","album":"Night","artist":"Ada","albumId":"al-1","track":1,""" +
                """"duration":200}]}}}"""
    }
}
