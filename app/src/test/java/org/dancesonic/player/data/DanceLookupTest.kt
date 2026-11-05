package org.dancesonic.player.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.dancesonic.player.dance.DanceMetadataSource
import org.dancesonic.player.dance.RecordingDance
import org.dancesonic.player.data.db.DanceSonicDatabase
import org.dancesonic.player.testsupport.TestSubsonicServer
import org.dancesonic.player.testsupport.newTestSyncStateStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    @Test
    fun `dance types and tempo from an answer are stored, and the tempo is kept with the lookup`() =
        runBlocking(Dispatchers.IO) {
            source.answers = mapOf("rec-1" to RecordingDance(listOf("Gavotte", "Gavotte"), bpm = 118, raters = 4))
            enqueueLibrary()

            repo.sync(server.client)

            assertEquals(listOf("Gavotte"), db.danceMetadataDao().danceTypesFor("rec-1"))
            assertEquals(118, db.danceLookupDao().bpmFor("rec-1"))
        }

    @Test
    fun `a recording answered without dance tags loses its old dance types`() =
        runBlocking(Dispatchers.IO) {
            source.answers = mapOf("rec-1" to RecordingDance(listOf("Gavotte"), bpm = null, raters = null))
            enqueueLibrary()
            repo.sync(server.client)
            source.answers = mapOf("rec-1" to RecordingDance(emptyList(), bpm = null, raters = null))
            enqueueLibrary()
            now = 1_000L + 30L * 24 * 60 * 60 * 1000 + 1

            repo.sync(server.client)

            assertEquals(emptyList<String>(), db.danceMetadataDao().danceTypesFor("rec-1"))
        }

    @Test
    fun `a recording the server does not answer for is recorded as checked with no tempo`() =
        runBlocking(Dispatchers.IO) {
            enqueueLibrary()

            repo.sync(server.client)

            assertNull(db.danceLookupDao().bpmFor("rec-1"))
            assertEquals(emptyList<String>(), db.danceMetadataDao().danceTypesFor("rec-1"))
            assertEquals(listOf("rec-1"), source.lookedUp)
        }

    @Test
    fun `lookups from another source are dropped and looked up again within the maximum age`() =
        runBlocking(Dispatchers.IO) {
            // The source is recorded in the sync state, so a change of source is noticed only with one.
            val withState =
                LibraryRepository(
                    db,
                    source,
                    clock = { now },
                    syncState = newTestSyncStateStore(ApplicationProvider.getApplicationContext()),
                )
            source.answers = mapOf("rec-1" to RecordingDance(listOf("Gavotte"), bpm = 120, raters = 2))
            // A sync with a state store asks for the extension list once; the client keeps the answer.
            server.enqueueJson(NO_EXTENSIONS)
            enqueueLibrary()
            withState.sync(server.client)
            source.key = "source-b"
            source.answers = emptyMap()
            enqueueLibrary()
            now = 2_000L

            withState.sync(server.client)

            assertEquals(listOf("rec-1", "rec-1"), source.lookedUp)
            assertEquals(emptyList<String>(), db.danceMetadataDao().danceTypesFor("rec-1"))
            assertNull(db.danceLookupDao().bpmFor("rec-1"))
        }

    private fun enqueueLibrary() {
        server.enqueueJson(ARTISTS)
        server.enqueueJson(ALBUM_LIST_ONE)
        server.enqueueJson(EMPTY_ALBUM_LIST)
        server.enqueueSongsOf(ALBUM_WITH_MBID)
    }

    /**
     * Records each looked-up recording. Answers with [answers] for the requested ids, and fails the first
     * [failNext] batches.
     */
    private class CountingSource : DanceMetadataSource {
        val lookedUp = mutableListOf<String>()
        var failNext = 0
        var answers: Map<String, RecordingDance> = emptyMap()
        var key = "source-a"

        override val sourceKey: String
            get() = key

        override suspend fun lookup(mbids: List<String>): Map<String, RecordingDance> {
            lookedUp += mbids
            if (failNext > 0) {
                failNext--
                throw IOException("DanceBrainz unreachable")
            }
            return answers.filterKeys { it in mbids }
        }
    }

    private companion object {
        const val ARTISTS =
            """{"subsonic-response":{"status":"ok","artists":{"index":[{"name":"A",""" +
                """"artist":[{"id":"ar-1","name":"Ada"}]}]}}}"""

        const val ALBUM_LIST_ONE =
            """{"subsonic-response":{"status":"ok","albumList2":{"album":[{"id":"al-1","name":"Night",""" +
                """"artistId":"ar-1","artist":"Ada","year":1999,"songCount":1,"duration":200}]}}}"""

        const val NO_EXTENSIONS = """{"subsonic-response":{"status":"ok","openSubsonicExtensions":[]}}"""

        const val EMPTY_ALBUM_LIST = """{"subsonic-response":{"status":"ok","albumList2":{"album":[]}}}"""

        const val ALBUM_WITH_MBID =
            """{"subsonic-response":{"status":"ok","album":{"id":"al-1","name":"Night","artist":"Ada",""" +
                """"artistId":"ar-1","year":1999,"songCount":1,"duration":200,"song":[""" +
                """{"id":"s-1","title":"One","album":"Night","artist":"Ada","albumId":"al-1","track":1,""" +
                """"duration":200,"musicBrainzId":"rec-1"}]}}}"""
    }
}
