package org.dancesonic.player.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.dancesonic.player.dance.DanceMetadataSource
import org.dancesonic.player.data.db.DanceSonicDatabase
import org.dancesonic.player.testsupport.TestSubsonicServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DanceLookupTest {
    private lateinit var db: DanceSonicDatabase
    private lateinit var server: TestSubsonicServer
    private lateinit var source: CountingSource
    private var now = 1_000L
    private lateinit var repo: LibraryRepository

    @Before
    fun setUp() {
        db =
            Room.inMemoryDatabaseBuilder(
                ApplicationProvider.getApplicationContext(),
                DanceSonicDatabase::class.java,
            ).allowMainThreadQueries().build()
        server = TestSubsonicServer()
        source = CountingSource()
        repo = LibraryRepository(db, source, clock = { now })
    }

    @After
    fun tearDown() {
        server.close()
        db.close()
    }

    @Test
    fun `a recording is not looked up again within the maximum age, even when DanceBrainz had nothing`() =
        runBlocking(Dispatchers.IO) {
            enqueueLibrary()
            repo.sync(server.client)
            enqueueLibrary()
            now = 2_000L

            repo.sync(server.client)

            assertEquals(listOf("rec-1"), source.lookedUp)
        }

    @Test
    fun `a recording is looked up again once its last lookup is older than the maximum age`() =
        runBlocking(Dispatchers.IO) {
            enqueueLibrary()
            repo.sync(server.client)
            enqueueLibrary()
            now = 1_000L + 30L * 24 * 60 * 60 * 1000 + 1

            repo.sync(server.client)

            assertEquals(listOf("rec-1", "rec-1"), source.lookedUp)
        }

    @Test
    fun `a failed lookup is retried on the next sync`() =
        runBlocking(Dispatchers.IO) {
            source.failNext = 1
            enqueueLibrary()
            val first = repo.sync(server.client)
            enqueueLibrary()

            val second = repo.sync(server.client)

            assertEquals(1, first.danceLookupsFailed)
            assertEquals(0, second.danceLookupsFailed)
            assertEquals(2, source.lookedUp.size)
        }

    private fun enqueueLibrary() {
        server.enqueueJson(ARTISTS)
        server.enqueueJson(ALBUM_LIST_ONE)
        server.enqueueJson(EMPTY_ALBUM_LIST)
        server.enqueueJson(ALBUM_WITH_MBID)
    }

    /** Records each lookup. Returns no information, and fails the first [failNext] lookups. */
    private class CountingSource : DanceMetadataSource {
        val lookedUp = mutableListOf<String>()
        var failNext = 0

        override suspend fun danceTypesFor(mbid: String): List<String>? {
            lookedUp += mbid
            if (failNext > 0) {
                failNext--
                throw IOException("DanceBrainz unreachable")
            }
            return null
        }
    }

    private companion object {
        const val ARTISTS =
            """{"subsonic-response":{"status":"ok","artists":{"index":[{"name":"A",""" +
                """"artist":[{"id":"ar-1","name":"Ada"}]}]}}}"""

        const val ALBUM_LIST_ONE =
            """{"subsonic-response":{"status":"ok","albumList2":{"album":[{"id":"al-1","name":"Night",""" +
                """"artistId":"ar-1","artist":"Ada","year":1999,"songCount":1,"duration":200}]}}}"""

        const val EMPTY_ALBUM_LIST = """{"subsonic-response":{"status":"ok","albumList2":{"album":[]}}}"""

        const val ALBUM_WITH_MBID =
            """{"subsonic-response":{"status":"ok","album":{"id":"al-1","name":"Night","artist":"Ada",""" +
                """"artistId":"ar-1","year":1999,"songCount":1,"duration":200,"song":[""" +
                """{"id":"s-1","title":"One","album":"Night","artist":"Ada","albumId":"al-1","track":1,""" +
                """"duration":200,"musicBrainzId":"rec-1"}]}}}"""
    }
}
