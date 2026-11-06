package org.dancesonic.player.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.mockwebserver.MockResponse
import org.dancesonic.player.dance.DanceBrainzRatingsClient
import org.dancesonic.player.data.db.DanceSonicDatabase
import org.dancesonic.player.data.db.ShoesEditEntity
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
class ShoesRepositoryTest {
    private val mbid = "7f9c2a1e-0d3b-4c5a-9e8f-1a2b3c4d5e6f"
    private lateinit var db: DanceSonicDatabase
    private lateinit var server: TestSubsonicServer

    /** The DanceBrainz address the repository uses. Switched to [UNREACHABLE] to simulate being offline. */
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

    private fun repo(token: String? = SIGNED_IN_TOKEN): ShoesRepository =
        ShoesRepository(
            ratings = db.shoesRatingDao(),
            edits = db.shoesEditDao(),
            tokens = tokenStoreFor(token),
            baseUrl = { base.toHttpUrl() },
            newClient = { DanceBrainzRatingsClient(it, server.plain) },
            clock = { now },
        )

    private fun viewOf(repo: ShoesRepository) = runBlocking { repo.observe().first()[mbid] }

    @Test
    fun `a rating is shown at once, sent, and no longer queued once DanceBrainz confirms it`() {
        server.server.enqueue(MockResponse().setResponseCode(204))
        val repo = repo()

        runBlocking { repo.rate(mbid, 4) }

        assertEquals("PUT", server.server.takeRequest().method)
        val view = viewOf(repo)
        assertEquals(4, view?.mine)
        assertFalse(view?.pending == true)
        assertNull(view?.error)
    }

    @Test
    fun `a clear is sent as a delete and shows no rating`() {
        server.server.enqueue(MockResponse().setResponseCode(204))
        val repo = repo()

        runBlocking { repo.clear(mbid) }

        assertEquals("DELETE", server.server.takeRequest().method)
        assertNull(viewOf(repo)?.mine)
    }

    @Test
    fun `a rating set while the phone is offline stays queued and visible`() {
        base = UNREACHABLE
        val repo = repo()

        runBlocking { repo.rate(mbid, 3) }

        val view = viewOf(repo)
        assertEquals(3, view?.mine)
        assertTrue(view?.pending == true)
        assertTrue(view?.error?.isNotBlank() == true)
    }

    @Test
    fun `a change the server rejects is kept in the queue with its error`() {
        server.server.enqueue(MockResponse().setResponseCode(400).setBody("{}"))
        val repo = repo()

        runBlocking { repo.rate(mbid, 5) }

        val view = viewOf(repo)
        assertEquals(5, view?.mine)
        assertTrue(view?.pending == true)
        assertTrue(view?.error.orEmpty().contains("400"))
    }

    @Test
    fun `flush sends a queued change once the server is back`() {
        base = UNREACHABLE
        val repo = repo()
        runBlocking { repo.rate(mbid, 2) }
        assertEquals(1, runBlocking { db.shoesEditDao().count() })

        base = server.server.url("/").toString()
        server.server.enqueue(MockResponse().setResponseCode(204))
        val remaining = runBlocking { repo.flush() }

        assertEquals(0, remaining)
        assertEquals("PUT", server.server.takeRequest().method)
        assertFalse(viewOf(repo)?.pending == true)
    }

    @Test
    fun `flush sends nothing while signed out, and the change stays queued`() {
        val repo = repo(token = null)
        runBlocking { db.shoesEditDao().upsert(ShoesEditEntity(mbid, 4, queuedAt = 1L, lastError = null)) }

        val remaining = runBlocking { repo.flush() }

        assertEquals(1, remaining)
        assertEquals(0, server.server.requestCount)
    }

    @Test
    fun `rating a track again replaces the queued change`() {
        base = UNREACHABLE
        val repo = repo()

        runBlocking {
            repo.rate(mbid, 2)
            repo.rate(mbid, 5)
        }

        assertEquals(5, viewOf(repo)?.mine)
        assertEquals(1, runBlocking { db.shoesEditDao().count() })
    }

    @Test
    fun `refresh reads the median and the viewer's rating, and does not read again within a day`() {
        server.server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("""{"recording":"$mbid","shoes_median":3.5,"shoes_raters":4,"my_shoes":2}"""),
        )
        val repo = repo()

        val failed = runBlocking { repo.refresh(listOf(mbid)) }
        now += ONE_HOUR_MILLIS
        val failedAgain = runBlocking { repo.refresh(listOf(mbid)) }

        assertEquals(0, failed)
        assertEquals(0, failedAgain)
        assertEquals(1, server.server.requestCount)
        val view = viewOf(repo)
        assertEquals(2, view?.mine)
        assertEquals(3.5, view?.median)
        assertEquals(4, view?.raters)
    }

    @Test
    fun `a queued change is shown over the rating read from the server`() {
        server.server.enqueue(MockResponse().setResponseCode(200).setBody("""{"shoes_raters":0,"my_shoes":2}"""))
        val repo = repo()
        runBlocking { repo.refresh(listOf(mbid)) }
        base = UNREACHABLE

        runBlocking { repo.rate(mbid, 5) }

        assertEquals(5, viewOf(repo)?.mine)
    }

    @Test
    fun `forgetting the account drops queued changes and ratings and reports how many were unsent`() {
        base = UNREACHABLE
        val repo = repo()
        runBlocking { repo.rate(mbid, 1) }

        val unsent = runBlocking { repo.forgetAccount() }

        assertEquals(1, unsent)
        assertTrue(runBlocking { repo.observe().first() }.isEmpty())
    }

    @Test
    fun `a recording without a valid MBID cannot be rated`() {
        val repo = repo()

        val error =
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { repo.rate("not-an-mbid", 3) }
            }

        assertTrue(error.message.orEmpty().contains("MusicBrainz"))
        assertEquals(0, server.server.requestCount)
    }

    private companion object {
        const val SIGNED_IN_TOKEN = "dbz_viewer_token_1234567890"
        const val UNREACHABLE = "https://127.0.0.1:1/"
        const val ONE_HOUR_MILLIS = 60L * 60 * 1000
    }
}
