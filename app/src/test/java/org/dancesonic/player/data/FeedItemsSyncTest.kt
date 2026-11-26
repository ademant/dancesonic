package org.dancesonic.player.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.dancesonic.player.dance.NoDanceMetadataSource
import org.dancesonic.player.data.db.DanceSonicDatabase
import org.dancesonic.player.data.db.TrackSize
import org.dancesonic.player.testsupport.TestSubsonicServer
import org.dancesonic.player.testsupport.newTestSyncStateStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

/**
 * Sync against a change feed that carries its items (`include=items`, version 2): the first sync and every change are
 * read from the feed's pages, with no listing and no per-album or per-artist request (#154). Clock is fixed at 1000.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FeedItemsSyncTest {
    private lateinit var db: DanceSonicDatabase
    private lateinit var server: TestSubsonicServer
    private lateinit var store: SyncStateStore
    private lateinit var repo: LibraryRepository

    @Before
    fun setUp() {
        db =
            Room
                .inMemoryDatabaseBuilder(
                    ApplicationProvider.getApplicationContext(),
                    DanceSonicDatabase::class.java,
                ).allowMainThreadQueries()
                .build()
        server = TestSubsonicServer()
        server.advertised = listOf("apiKeyAuthentication", "tinysonicLibraryChanges")
        server.versions = mapOf("tinysonicLibraryChanges" to listOf(1, 2))
        store = newTestSyncStateStore(ApplicationProvider.getApplicationContext())
        repo = LibraryRepository(db, NoDanceMetadataSource, clock = { 1_000L }, syncState = store)
    }

    @After
    fun tearDown() {
        server.close()
        db.close()
    }

    /** Requests already read by [requestedPaths]; the server's count never goes down. */
    private var taken = 0

    /** The paths of the requests since the last call, extension requests left out. */
    private fun requestedPaths(): List<String> {
        val count = server.server.requestCount - taken
        taken += count
        return List(count) {
            server.server
                .takeRequest(1, TimeUnit.SECONDS)
                ?.path
                .orEmpty()
        }.filterNot { it.startsWith("/rest/getOpenSubsonicExtensions.view") }
    }

    private fun firstSync() =
        runBlocking(Dispatchers.IO) {
            server.enqueueJson(FULL_PAGE_1)
            server.enqueueJson(FULL_PAGE_2)
            repo.syncIncremental(server.client)
        }

    @Test
    fun `the first sync reads the library from the feed with items, page by page, and nothing else`() {
        val result = firstSync()

        assertEquals(SyncMode.Full, result.mode)
        assertEquals(1, runBlocking { db.artistDao().count() })
        assertEquals(1, runBlocking { db.albumDao().count() })
        assertEquals(2, runBlocking { db.trackDao().count() })
        assertEquals(5000L, runBlocking { store.serverTime(server.settings.baseUrl.toString()) })
        val paths = requestedPaths()
        assertEquals(2, paths.size)
        assertTrue(paths.all { it.startsWith("/rest/getLibraryChanges.view") && "include=items" in it })
        assertTrue("cursor=c2" in paths[1])
    }

    @Test
    fun `a change sync takes the items from the feed without fetching any one by one`() {
        firstSync()
        requestedPaths()
        server.enqueueJson(INFO_NEW)
        server.enqueueJson(CHANGES_WITH_ITEMS)

        val result = runBlocking(Dispatchers.IO) { repo.syncIncremental(server.client) }

        assertEquals(SyncMode.Changes, result.mode)
        assertEquals("Night (remaster)", runBlocking { db.albumDao().get("al-1") }?.name)
        assertEquals("Uno", runBlocking { db.trackDao().get("s-1") }?.title)
        assertNull(runBlocking { db.trackDao().get("s-2") })
        val added = runBlocking { db.trackDao().get("s-3") }
        assertNotNull(added)
        // The new song names no artist, so it takes its album's.
        assertEquals("Ada", added?.artistName)
        assertEquals(6000L, runBlocking { store.serverTime(server.settings.baseUrl.toString()) })
        val paths = requestedPaths()
        assertTrue(paths.none { "getAlbum.view" in it || "getArtist.view" in it })
        assertTrue(paths.single { it.startsWith("/rest/getLibraryChanges.view") }.contains("include=items"))
    }

    @Test
    fun `an album change keeps the album's songs that the feed does not list`() {
        firstSync()
        server.enqueueJson(INFO_NEW)
        server.enqueueJson(ALBUM_ONLY_WITH_ITEM)

        runBlocking(Dispatchers.IO) { repo.syncIncremental(server.client) }

        assertEquals("Night (remaster)", runBlocking { db.albumDao().get("al-1") }?.name)
        assertEquals(2, runBlocking { db.trackDao().count() })
    }

    @Test
    fun `a full answer with items rebuilds the library from that answer without asking again`() {
        firstSync()
        requestedPaths()
        server.enqueueJson(INFO_NEW)
        server.enqueueJson(FULL_WITH_ONE_SONG)

        val result = runBlocking(Dispatchers.IO) { repo.syncIncremental(server.client) }

        assertEquals(SyncMode.Full, result.mode)
        assertNull(runBlocking { db.trackDao().get("s-2") })
        assertNotNull(runBlocking { db.trackDao().get("s-1") })
        assertEquals(7000L, runBlocking { store.serverTime(server.settings.baseUrl.toString()) })
        assertEquals(2, requestedPaths().size)
    }

    @Test
    fun `an entry whose item is gone is left for the next sync and not fetched`() {
        firstSync()
        requestedPaths()
        server.enqueueJson(INFO_NEW)
        server.enqueueJson(SONG_WITHOUT_ITEM)

        runBlocking(Dispatchers.IO) { repo.syncIncremental(server.client) }

        assertEquals("One", runBlocking { db.trackDao().get("s-1") }?.title)
        assertTrue(requestedPaths().none { "getSong.view" in it })
    }

    @Test
    fun `the sizes of stored tracks are there for the stream source`() {
        firstSync()

        val sizes = runBlocking { db.trackDao().observeSizes().first() }

        assertEquals(listOf(TrackSize("s-1", 3_000_000L)), sizes)
    }

    @Test
    fun `a server with a change feed needs no one-track check before playing, others do`() {
        assertTrue(runBlocking { repo.hasChangeFeed(server.client) })

        TestSubsonicServer().use { plain ->
            assertFalse(runBlocking { repo.hasChangeFeed(plain.client) })
        }
    }

    private companion object {
        const val ARTIST = """{"id":"ar-1","updated":900,"item":{"id":"ar-1","name":"Ada","albumCount":1}}"""

        const val ALBUM =
            """{"id":"al-1","updated":900,"item":{"id":"al-1","name":"Night","artist":"Ada","artistId":"ar-1",""" +
                """"year":1999,"songCount":2,"duration":380}}"""

        const val SONG_1 =
            """{"id":"s-1","updated":900,"item":{"id":"s-1","title":"One","album":"Night","artist":"Ada",""" +
                """"albumId":"al-1","track":1,"duration":200,"size":3000000,"musicBrainzId":"rec-1"}}"""

        const val SONG_2 =
            """{"id":"s-2","updated":900,"item":{"id":"s-2","title":"Two","album":"Night","artist":"Ada",""" +
                """"albumId":"al-1","track":2,"duration":180}}"""

        const val FULL_PAGE_1 =
            """{"subsonic-response":{"status":"ok","libraryChanges":{"full":true,"serverTime":5000,""" +
                """"artists":[$ARTIST],"albums":[$ALBUM],"songs":[$SONG_1],"playlists":[],"starred":[],""" +
                """"deleted":[],"nextCursor":"c2"}}}"""

        const val FULL_PAGE_2 =
            """{"subsonic-response":{"status":"ok","libraryChanges":{"full":true,"serverTime":5000,""" +
                """"artists":[],"albums":[],"songs":[$SONG_2],"playlists":[],"starred":[],"deleted":[]}}}"""

        const val INFO_NEW =
            """{"subsonic-response":{"status":"ok","tinysonicLibraryInfo":{"artists":1,"albums":1,"songs":2,""" +
                """"duration":380,"lastModified":"2026-11-26T12:00:00Z"}}}"""

        const val ALBUM_RENAMED =
            """{"id":"al-1","updated":5500,"item":{"id":"al-1","name":"Night (remaster)","artist":"Ada",""" +
                """"artistId":"ar-1","year":1999,"songCount":2,"duration":380}}"""

        const val CHANGES_WITH_ITEMS =
            """{"subsonic-response":{"status":"ok","libraryChanges":{"full":false,"serverTime":6000,""" +
                """"artists":[],"albums":[$ALBUM_RENAMED],"songs":[""" +
                """{"id":"s-1","updated":5500,"item":{"id":"s-1","title":"Uno","album":"Night","artist":"Ada",""" +
                """"albumId":"al-1","track":1,"duration":200}},""" +
                """{"id":"s-3","updated":5500,"item":{"id":"s-3","title":"Three","album":"Night",""" +
                """"albumId":"al-1","track":3,"duration":210}}],""" +
                """"playlists":[],"starred":[],"deleted":[{"type":"song","id":"s-2","deleted":5500}]}}}"""

        const val ALBUM_ONLY_WITH_ITEM =
            """{"subsonic-response":{"status":"ok","libraryChanges":{"full":false,"serverTime":6000,""" +
                """"artists":[],"albums":[$ALBUM_RENAMED],"songs":[],"playlists":[],"starred":[],"deleted":[]}}}"""

        const val FULL_WITH_ONE_SONG =
            """{"subsonic-response":{"status":"ok","libraryChanges":{"full":true,"serverTime":7000,""" +
                """"artists":[$ARTIST],"albums":[$ALBUM],"songs":[$SONG_1],"playlists":[],"starred":[],""" +
                """"deleted":[]}}}"""

        const val SONG_WITHOUT_ITEM =
            """{"subsonic-response":{"status":"ok","libraryChanges":{"full":false,"serverTime":6000,""" +
                """"artists":[],"albums":[],"songs":[{"id":"s-1","updated":5500}],"playlists":[],""" +
                """"starred":[],"deleted":[]}}}"""
    }
}
