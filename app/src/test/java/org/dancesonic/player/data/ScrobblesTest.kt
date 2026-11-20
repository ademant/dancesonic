package org.dancesonic.player.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.dancesonic.player.data.db.DanceSonicDatabase
import org.dancesonic.player.data.db.ScrobbleQueueEntity
import org.dancesonic.player.subsonic.PlayReporter
import org.dancesonic.player.subsonic.ServerSettings
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
class ScrobblesTest {
    private lateinit var db: DanceSonicDatabase
    private lateinit var server: TestSubsonicServer
    private lateinit var scrobbles: Scrobbles
    private var current: ServerSettings? = null

    @Before
    fun setUp() {
        db =
            Room.inMemoryDatabaseBuilder(
                ApplicationProvider.getApplicationContext(),
                DanceSonicDatabase::class.java,
            ).allowMainThreadQueries().build()
        server = TestSubsonicServer()
        current = server.settings
        scrobbles = Scrobbles(db, currentServer = { current }, reporterFor = { PlayReporter(it, server.http) })
    }

    @After
    fun tearDown() {
        server.close()
        db.close()
    }

    private val serverUrl get() = server.settings.baseUrl.toString()

    private fun pending() = runBlocking { db.scrobbleQueueDao().pending(serverUrl) }

    private fun takeTime(): String? = server.server.takeRequest().requestUrl?.queryParameter("time")

    @Test
    fun `an accepted play is sent with the time it started, then removed`() =
        runBlocking {
            server.enqueueJson(OK)

            scrobbles.record("song-a", 1_000L)

            val url = server.server.takeRequest().requestUrl!!
            assertEquals("/rest/scrobble.view", url.encodedPath)
            assertEquals("song-a", url.queryParameter("id"))
            assertEquals("1000", url.queryParameter("time"))
            assertEquals("true", url.queryParameter("submission"))
            assertTrue(pending().isEmpty())
        }

    @Test
    fun `plays the server cannot take wait, and go out in order with their own start times`() =
        runBlocking {
            // The first play fails, so it stays queued.
            server.enqueueError(503)
            scrobbles.record("song-a", 100L)
            assertEquals(1, pending().size)

            server.enqueueJson(OK)
            server.enqueueJson(OK)
            scrobbles.record("song-b", 200L)

            assertTrue(pending().isEmpty())
            // The failed send, then the first play's accepted send, then the second play.
            val times = List(3) { takeTime() }
            assertEquals(listOf("100", "100", "200"), times)
        }

    @Test
    fun `a play the server refuses is dropped, and the plays behind it still go out`() =
        runBlocking {
            server.enqueueJson(FAILED)
            scrobbles.record("missing-song", 100L)
            assertTrue(pending().isEmpty())

            server.enqueueJson(OK)
            scrobbles.record("song-b", 200L)

            assertEquals(2, server.server.requestCount)
            assertEquals("missing-song", server.server.takeRequest().requestUrl?.queryParameter("id"))
            assertEquals("song-b", server.server.takeRequest().requestUrl?.queryParameter("id"))
        }

    @Test
    fun `plays are sent only to the server they were made on, until the connection is forgotten`() =
        runBlocking {
            server.enqueueError(503)
            scrobbles.record("song-a", 100L)
            assertEquals(1, pending().size)

            // Another server is now the current one: nothing is sent, and the play waits for its own server.
            current = ServerSettings("https://other.example/".toHttpUrl(), "other-key")
            scrobbles.flush()
            assertEquals(1, server.server.requestCount)

            current = server.settings
            assertEquals(1, pending().size)

            scrobbles.forgetAll()
            assertTrue(pending().isEmpty())
        }

    @Test
    fun `the queue keeps the newest plays when it is full`() =
        runBlocking {
            val dao = db.scrobbleQueueDao()
            repeat(Scrobbles.MAX_PENDING + 5) {
                dao.insert(ScrobbleQueueEntity(serverUrl = serverUrl, songId = "old-$it", playedAt = it.toLong()))
            }
            server.enqueueError(503)

            scrobbles.record("newest", 99_999L)

            val kept = pending()
            assertEquals(Scrobbles.MAX_PENDING, kept.size)
            assertEquals("old-6", kept.first().songId)
            assertEquals("newest", kept.last().songId)
        }

    private companion object {
        const val OK = """{"subsonic-response":{"status":"ok","version":"1.16.1"}}"""
        const val FAILED =
            """{"subsonic-response":{"status":"failed","version":"1.16.1",""" +
                """"error":{"code":70,"message":"The requested data was not found."}}}"""
    }
}
