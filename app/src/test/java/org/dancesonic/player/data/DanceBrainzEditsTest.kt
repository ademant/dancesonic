package org.dancesonic.player.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.mockwebserver.MockResponse
import org.dancesonic.player.dance.DanceBrainzRatingsClient
import org.dancesonic.player.dance.DanceBrainzTagsClient
import org.dancesonic.player.dance.DanceSuggestion
import org.dancesonic.player.data.db.DanceSonicDatabase
import org.dancesonic.player.data.db.EditEntity
import org.dancesonic.player.data.db.EditKind
import org.dancesonic.player.data.db.EditStatus
import org.dancesonic.player.testsupport.TestSubsonicServer
import org.dancesonic.player.testsupport.tokenStoreFor
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DanceBrainzEditsTest {
    private val mbid = "7f9c2a1e-0d3b-4c5a-9e8f-1a2b3c4d5e6f"
    private lateinit var db: DanceSonicDatabase
    private lateinit var server: TestSubsonicServer

    /** The DanceBrainz address. Switched to [UNREACHABLE] to simulate being offline. */
    private var base: String = ""
    private var now = 1_000_000L

    @Before
    fun setUp() {
        db =
            Room.inMemoryDatabaseBuilder(
                ApplicationProvider.getApplicationContext(),
                DanceSonicDatabase::class.java,
            ).allowMainThreadQueries().build()
        server = TestSubsonicServer()
        base = server.server.url("/").toString()
    }

    @After
    fun tearDown() {
        server.close()
        db.close()
    }

    private fun edits(token: String? = SIGNED_IN_TOKEN): DanceBrainzEdits =
        DanceBrainzEdits(
            database = db,
            tokens = tokenStoreFor(token),
            baseUrl = { base.toHttpUrl() },
            newClients = {
                DanceBrainzClients(
                    DanceBrainzRatingsClient(it, server.plain),
                    DanceBrainzTagsClient(it, server.plain),
                )
            },
            clock = { now },
        )

    private fun shoesOf(repo: DanceBrainzEdits) = runBlocking { repo.observeShoes().first()[mbid] }

    private fun queued(): Int = runBlocking { db.editQueueDao().withStatus(EditStatus.QUEUED).size }

    private fun held(): List<EditEntity> = runBlocking { db.editQueueDao().withStatus(EditStatus.PENDING_REVIEW) }

    private fun seedShown(vararg names: String) =
        runBlocking { db.danceMetadataDao().replaceFor(mbid, names.toList(), fetchedAt = now) }

    private fun json(body: String): MockResponse = MockResponse().setResponseCode(200).setBody(body)

    private fun tag(
        id: Long,
        name: String,
        qualifier: String,
    ): String = """{"tag":{"id":$id,"type":"dance","name":"$name"},"qualifier":"$qualifier"}"""

    private fun tracks(vararg entries: String): String =
        """{"recording":"$mbid","tags":[${entries.joinToString(",")}]}"""

    // --- shoes ---

    @Test
    fun `a rating is shown at once, sent, and no longer queued once DanceBrainz confirms it`() {
        server.server.enqueue(MockResponse().setResponseCode(204))
        val repo = edits()

        runBlocking { repo.rate(mbid, 4) }

        assertEquals("PUT", server.server.takeRequest().method)
        val view = shoesOf(repo)
        assertEquals(4, view?.mine)
        assertFalse(view?.pending == true)
        assertEquals(0, queued())
    }

    @Test
    fun `a clear is sent as a delete and shows no rating`() {
        server.server.enqueue(MockResponse().setResponseCode(204))
        val repo = edits()

        runBlocking { repo.clearRating(mbid) }

        assertEquals("DELETE", server.server.takeRequest().method)
        assertNull(shoesOf(repo)?.mine)
    }

    @Test
    fun `a rating set while the phone is offline stays queued and visible`() {
        base = UNREACHABLE
        val repo = edits()

        runBlocking { repo.rate(mbid, 3) }

        val view = shoesOf(repo)
        assertEquals(3, view?.mine)
        assertTrue(view?.pending == true)
        assertTrue(view?.error?.isNotBlank() == true)
        assertEquals(1, queued())
    }

    @Test
    fun `a rating the server rejects is kept in the queue with its error`() {
        server.server.enqueue(MockResponse().setResponseCode(400).setBody("{}"))
        val repo = edits()

        runBlocking { repo.rate(mbid, 5) }

        val view = shoesOf(repo)
        assertEquals(5, view?.mine)
        assertTrue(view?.pending == true)
        assertTrue(view?.error.orEmpty().contains("400"))
    }

    @Test
    fun `flush sends a queued rating once the server is back`() {
        base = UNREACHABLE
        val repo = edits()
        runBlocking { repo.rate(mbid, 2) }
        assertEquals(1, queued())

        base = server.server.url("/").toString()
        server.server.enqueue(MockResponse().setResponseCode(204))
        val remaining = runBlocking { repo.flush() }

        assertEquals(0, remaining)
        assertEquals("PUT", server.server.takeRequest().method)
        assertFalse(shoesOf(repo)?.pending == true)
    }

    @Test
    fun `flush sends nothing while signed out, and the change stays queued`() {
        val repo = edits(token = null)
        runBlocking {
            db.editQueueDao().insert(
                EditEntity(kind = EditKind.SHOES, mbid = mbid, value = 4, queuedAt = 1L),
            )
        }

        val remaining = runBlocking { repo.flush() }

        assertEquals(1, remaining)
        assertEquals(0, server.server.requestCount)
    }

    @Test
    fun `rating a track again replaces the queued rating`() {
        base = UNREACHABLE
        val repo = edits()

        runBlocking {
            repo.rate(mbid, 2)
            repo.rate(mbid, 5)
        }

        assertEquals(5, shoesOf(repo)?.mine)
        assertEquals(1, queued())
    }

    @Test
    fun `refresh reads the median and the viewer's rating, and does not read again within a day`() {
        server.server.enqueue(
            json("""{"recording":"$mbid","shoes_median":3.5,"shoes_raters":4,"my_shoes":2}"""),
        )
        val repo = edits()

        val failed = runBlocking { repo.refreshShoes(listOf(mbid)) }
        now += ONE_HOUR_MILLIS
        val failedAgain = runBlocking { repo.refreshShoes(listOf(mbid)) }

        assertEquals(0, failed)
        assertEquals(0, failedAgain)
        assertEquals(1, server.server.requestCount)
        val view = shoesOf(repo)
        assertEquals(2, view?.mine)
        assertEquals(3.5, view?.median)
        assertEquals(4, view?.raters)
    }

    @Test
    fun `a queued rating is shown over the rating read from the server`() {
        server.server.enqueue(json("""{"shoes_raters":0,"my_shoes":2}"""))
        val repo = edits()
        runBlocking { repo.refreshShoes(listOf(mbid)) }
        base = UNREACHABLE

        runBlocking { repo.rate(mbid, 5) }

        assertEquals(5, shoesOf(repo)?.mine)
    }

    @Test
    fun `forgetting the account drops queued changes and ratings and reports how many were unsent`() {
        base = UNREACHABLE
        val repo = edits()
        runBlocking { repo.rate(mbid, 1) }

        val unsent = runBlocking { repo.forgetAccount() }

        assertEquals(1, unsent)
        assertTrue(runBlocking { repo.observeShoes().first() }.isEmpty())
    }

    @Test
    fun `a recording without a valid MBID cannot be rated`() {
        val repo = edits()

        val error =
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { repo.rate("not-an-mbid", 3) }
            }

        assertTrue(error.message.orEmpty().contains("MusicBrainz"))
        assertEquals(0, server.server.requestCount)
    }

    // --- dances ---

    @Test
    fun `adding a dance posts it with its path and qualifier, and it leaves the queue once applied`() {
        server.server.enqueue(json("""{"change":{"status":"applied"}}"""))
        val repo = edits()

        runBlocking {
            repo.addDance(
                mbid,
                DanceSuggestion("gavotte/gavotte des montagnes", "gavotte des montagnes"),
                "disco",
            )
        }

        val request = server.server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/v1/recordings/$mbid/tags", request.requestUrl?.encodedPath)
        assertEquals(
            """{"tags":[{"type":"dance","path":"gavotte/gavotte des montagnes","qualifier":"disco"}]}""",
            request.body.readUtf8(),
        )
        assertEquals(0, queued())
    }

    @Test
    fun `a dance held by a moderated server stays in the queue as pending review and is not sent again`() {
        server.server.enqueue(MockResponse().setResponseCode(202).setBody("""{"change":{"status":"pending"}}"""))
        val repo = edits()

        runBlocking { repo.addDance(mbid, DanceSuggestion("valse", "valse"), "both") }
        val remaining = runBlocking { repo.flush() }

        assertEquals(0, remaining)
        assertEquals(1, held().size)
        assertEquals(1, server.server.requestCount)
        assertEquals(EditKind.DANCE_ADD, held().single().kind)
    }

    @Test
    fun `removing a dance replaces the dance tags with the rest, keeping their qualifiers`() {
        seedShown("gavotte", "valse")
        server.server.enqueue(json(tracks(tag(1, "gavotte", "both"), tag(2, "valse", "learn"))))
        server.server.enqueue(json("""{"change":{"status":"applied"}}"""))
        val repo = edits()

        runBlocking { repo.removeDance(mbid, "gavotte") }

        assertEquals("GET", server.server.takeRequest().method)
        val replace = server.server.takeRequest()
        assertEquals("PUT", replace.method)
        assertEquals("type=dance", replace.requestUrl?.encodedQuery)
        assertEquals("""{"tags":[{"tag_id":2,"qualifier":"learn"}]}""", replace.body.readUtf8())
        assertEquals(0, queued())
    }

    @Test
    fun `a dance that changed on DanceBrainz since the phone showed it is a conflict, and nothing is sent`() {
        seedShown("gavotte", "valse")
        server.server.enqueue(
            json(tracks(tag(1, "gavotte", "both"), tag(2, "valse", "learn"), tag(3, "tango", "both"))),
        )
        val repo = edits()

        runBlocking { repo.removeDance(mbid, "gavotte") }

        assertEquals(1, server.server.requestCount)
        val conflicts = runBlocking { db.editQueueDao().withStatus(EditStatus.CONFLICT) }
        assertEquals(1, conflicts.size)
        assertTrue(conflicts.single().lastError.orEmpty().contains("changed"))
    }

    @Test
    fun `removing a dance that is already gone on DanceBrainz clears the change without sending anything`() {
        seedShown("gavotte", "valse")
        server.server.enqueue(json(tracks(tag(2, "valse", "learn"))))
        val repo = edits()

        runBlocking { repo.removeDance(mbid, "gavotte") }

        assertEquals(1, server.server.requestCount)
        assertEquals(0, queued())
    }

    @Test
    fun `a dance removal held by a moderated server is pending review`() {
        seedShown("gavotte", "valse")
        server.server.enqueue(json(tracks(tag(1, "gavotte", "both"), tag(2, "valse", "learn"))))
        server.server.enqueue(MockResponse().setResponseCode(202).setBody("""{"change":{"status":"pending"}}"""))
        val repo = edits()

        runBlocking { repo.removeDance(mbid, "gavotte") }

        assertEquals(EditKind.DANCE_REMOVE, held().single().kind)
    }

    @Test
    fun `dismissing a change removes it from the queue`() {
        val repo = edits(token = null)
        runBlocking { repo.addDance(mbid, DanceSuggestion("valse", "valse"), "both") }
        val id = runBlocking { db.editQueueDao().withStatus(EditStatus.QUEUED).single().id }

        runBlocking { repo.dismiss(id) }

        assertEquals(0, runBlocking { db.editQueueDao().withStatus(EditStatus.QUEUED).size })
    }

    @Test
    fun `a dance qualifier outside learn, disco and both is refused before anything is sent`() {
        val repo = edits()

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { repo.addDance(mbid, DanceSuggestion("valse", "valse"), "always") }
        }

        assertEquals(0, server.server.requestCount)
    }

    @Test
    fun `suggestions are the values with a path, and nothing when the server answers null`() {
        server.server.enqueue(
            json("""[{"tag":{"id":4,"type":"dance","name":"valse"},"distance":0,"path":"valse"}]"""),
        )
        server.server.enqueue(json("null"))
        val repo = edits()

        val found = runBlocking { repo.suggestDances("valse") }
        val none = runBlocking { repo.suggestDances("zzz") }

        assertEquals(listOf(DanceSuggestion("valse", "valse", tagId = 4)), found)
        assertEquals(emptyList<DanceSuggestion>(), none)
    }

    private companion object {
        const val SIGNED_IN_TOKEN = "dbz_viewer_token_1234567890"
        const val UNREACHABLE = "https://127.0.0.1:1/"
        const val ONE_HOUR_MILLIS = 60L * 60 * 1000
    }
}
