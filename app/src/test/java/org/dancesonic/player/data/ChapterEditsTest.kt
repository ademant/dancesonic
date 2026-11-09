package org.dancesonic.player.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.mockwebserver.MockResponse
import org.dancesonic.player.dance.ChapterChange
import org.dancesonic.player.dance.DanceBrainzChaptersClient
import org.dancesonic.player.dance.DanceBrainzException
import org.dancesonic.player.data.db.DanceSonicDatabase
import org.dancesonic.player.testsupport.TestSubsonicServer
import org.dancesonic.player.testsupport.tokenStoreFor
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ChapterEditsTest {
    private val mbid = "5ec2d0c5-03e3-4d8b-9ad2-0c11a6b7a5d1"
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

    private fun edits(signedIn: Boolean = true): ChapterEdits =
        ChapterEdits(
            database = db,
            tokens = tokenStoreFor(if (signedIn) "dbz_viewer_token_1234567890" else null),
            baseUrl = { server.server.url("/").toString().toHttpUrl() },
            newClient = { DanceBrainzChaptersClient(it, server.plain) },
        )

    @Test
    fun `a write goes against the version read just before it, and the list is stored after it`() {
        server.server.enqueue(json(VERSION_1, "Ton simpl"))
        server.server.enqueue(MockResponse().setResponseCode(200).setBody(EMPTY_CHANGE))
        server.server.enqueue(json(VERSION_2, "Ton simpl renamed"))

        val pending = runBlocking { edits().update(mbid, 0, ChapterChange(title = "Ton simpl renamed")) }

        assertEquals("GET", server.server.takeRequest().method)
        val write = server.server.takeRequest()
        assertEquals("PATCH", write.method)
        assertEquals("\"$VERSION_1\"", write.getHeader("If-Match"))
        assertEquals("GET", server.server.takeRequest().method)
        assertFalse(pending)
        assertEquals("Ton simpl renamed", runBlocking { db.chapterDao().getFor(mbid) }.single().title)
    }

    @Test
    fun `a refused version reloads the stored list before the error is shown`() {
        server.server.enqueue(json(VERSION_1, "Ton simpl"))
        server.server.enqueue(MockResponse().setResponseCode(412).setBody("""{"error":"version_mismatch"}"""))
        server.server.enqueue(json(VERSION_2, "someone else's name"))

        val error =
            assertThrows(DanceBrainzException::class.java) {
                runBlocking { edits().update(mbid, 0, ChapterChange(title = "mine")) }
            }

        assertEquals(412, error.httpCode)
        assertEquals("someone else's name", runBlocking { db.chapterDao().getFor(mbid) }.single().title)
    }

    @Test
    fun `without a token nothing is sent`() {
        val error =
            assertThrows(DanceBrainzException::class.java) {
                runBlocking { edits(signedIn = false).delete(mbid, 0) }
            }

        assertTrue(error.message.orEmpty().contains("Sign in"))
        assertEquals(0, server.server.requestCount)
    }

    private fun json(
        version: String,
        title: String,
    ): MockResponse =
        MockResponse().setResponseCode(200).setBody(
            """{"mbid":"$mbid","length_ms":300000,"version":"$version",
               "chapters":[{"start_ms":0,"end_ms":300000,"title":"$title","dance":null}]}""",
        )

    private companion object {
        const val VERSION_1 = "v1"
        const val VERSION_2 = "v2"
        const val EMPTY_CHANGE = """{"change":{"status":"applied"},"chapters":[]}"""
    }
}
