package org.dancesonic.player.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.dancesonic.player.dance.NoDanceMetadataSource
import org.dancesonic.player.data.db.DanceSonicDatabase
import org.dancesonic.player.subsonic.SubsonicException
import org.dancesonic.player.testsupport.TestSubsonicServer
import org.dancesonic.player.testsupport.newTestSyncStateStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Sync against a folksonic-style change feed, and the Subsonic comparison fallback. Clock is fixed at 1000 ms. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FeedSyncTest {
    private lateinit var db: DanceSonicDatabase
    private lateinit var server: TestSubsonicServer
    private lateinit var store: SyncStateStore
    private lateinit var repo: LibraryRepository

    @Before
    fun setUp() {
        db =
            Room.inMemoryDatabaseBuilder(
                ApplicationProvider.getApplicationContext(),
                DanceSonicDatabase::class.java,
            ).allowMainThreadQueries().build()
        server = TestSubsonicServer()
        store = newTestSyncStateStore(ApplicationProvider.getApplicationContext())
        repo = LibraryRepository(db, NoDanceMetadataSource, clock = { 1_000L }, syncState = store)
    }

    @After
    fun tearDown() {
        server.close()
        db.close()
    }

    @Test
    fun `first sync on a folksonic server stores the change-feed point`() =
        runBlocking(Dispatchers.IO) {
            enqueueFirstSync()

            val result = repo.syncIncremental(server.client)

            assertEquals(SyncMode.Full, result.mode)
            assertEquals(1000L, store.serverTime(serverUrl()))
            assertEquals(1, db.albumDao().count())
        }

    @Test
    fun `unchanged library is one info request and writes nothing`() =
        runBlocking(Dispatchers.IO) {
            enqueueFirstSync()
            repo.syncIncremental(server.client)
            val requestsBefore = server.server.requestCount
            server.enqueueJson(INFO_OLD)

            val result = repo.syncIncremental(server.client)

            assertEquals(SyncMode.Unchanged, result.mode)
            assertEquals(0, result.rowsWritten)
            assertEquals(requestsBefore + 1, server.server.requestCount)
            assertEquals(2, db.trackDao().count())
        }

    @Test
    fun `changed album is refetched and a deleted song is removed`() =
        runBlocking(Dispatchers.IO) {
            enqueueFirstSync()
            repo.syncIncremental(server.client)
            server.enqueueJson(INFO_NEW)
            server.enqueueJson(FEED_ALBUM_AND_DELETED_SONG)
            server.enqueueJson(ALBUM_WITHOUT_SECOND_SONG)

            val result = repo.syncIncremental(server.client)

            assertEquals(SyncMode.Changes, result.mode)
            assertNull(db.trackDao().get("s-2"))
            assertNotNull(db.trackDao().get("s-1"))
            assertEquals(2000L, store.serverTime(serverUrl()))
        }

    @Test
    fun `a feed that reports full triggers a full listing`() =
        runBlocking(Dispatchers.IO) {
            enqueueFirstSync()
            repo.syncIncremental(server.client)
            server.enqueueJson(INFO_NEW)
            server.enqueueJson(FEED_FULL)
            // The full listing reads the change point again, then the whole library.
            server.enqueueJson(CHECKPOINT_AT_3000)
            server.enqueueJson(ARTISTS)
            server.enqueueJson(ALBUM_LIST_ONE)
            server.enqueueJson(EMPTY_ALBUM_LIST)
            server.enqueueSongsOf(ALBUM_FULL)

            val result = repo.syncIncremental(server.client)

            assertEquals(SyncMode.Full, result.mode)
            assertEquals(3000L, store.serverTime(serverUrl()))
        }

    @Test
    fun `a failed album fetch keeps the old change-feed point`() =
        runBlocking(Dispatchers.IO) {
            enqueueFirstSync()
            repo.syncIncremental(server.client)
            server.enqueueJson(INFO_NEW)
            server.enqueueJson(FEED_ALBUM_AND_DELETED_SONG)
            server.enqueueJson(FAILED_NOT_RETRIED)

            assertThrows(SubsonicException::class.java) {
                runBlocking(Dispatchers.IO) { repo.syncIncremental(server.client) }
            }
            assertEquals(1000L, store.serverTime(serverUrl()))
        }

    @Test
    fun `an album the server no longer has is skipped and the point still advances`() =
        runBlocking(Dispatchers.IO) {
            enqueueFirstSync()
            repo.syncIncremental(server.client)
            server.enqueueJson(INFO_NEW)
            server.enqueueJson(FEED_ALBUM_AND_DELETED_SONG)
            server.enqueueJson(NOT_FOUND)

            val result = repo.syncIncremental(server.client)

            assertEquals(1, result.skippedAlbums)
            assertEquals(2000L, store.serverTime(serverUrl()))
        }

    @Test
    fun `without a change feed the comparison fetches only the albums that differ`() =
        runBlocking(Dispatchers.IO) {
            server.advertised = listOf("apiKeyAuthentication")
            server.enqueueJson(ARTISTS)
            server.enqueueJson(ALBUM_LIST_ONE)
            server.enqueueJson(EMPTY_ALBUM_LIST)
            server.enqueueJson(ALBUM_FULL)

            val first = repo.syncIncremental(server.client)

            assertEquals(SyncMode.Changes, first.mode)
            assertEquals(2, db.trackDao().count())

            server.enqueueJson(ARTISTS)
            server.enqueueJson(ALBUM_LIST_ONE)
            server.enqueueJson(EMPTY_ALBUM_LIST)

            val second = repo.syncIncremental(server.client)

            assertEquals(SyncMode.Unchanged, second.mode)
            assertEquals(0, second.rowsWritten)
            assertNull(store.serverTime(serverUrl()))
        }

    @Test
    fun `fallback removes an album with its tracks and keeps playlist entries that named them`() =
        runBlocking(Dispatchers.IO) {
            server.advertised = listOf("apiKeyAuthentication")
            server.enqueueJson(ARTISTS)
            server.enqueueJson(ALBUM_LIST_ONE)
            server.enqueueJson(EMPTY_ALBUM_LIST)
            server.enqueueJson(ALBUM_FULL)
            repo.syncIncremental(server.client)
            val playlists = PlaylistRepository(db, clock = { 1_000L })
            val playlistId = playlists.create("Waltz course")
            playlists.addTrack(playlistId, "s-1", "One")

            server.enqueueJson(ARTISTS)
            server.enqueueJson(EMPTY_ALBUM_LIST)
            repo.syncIncremental(server.client)

            assertNull(db.albumDao().get("al-1"))
            assertNull(db.trackDao().get("s-1"))
            assertEquals(0, db.trackDao().count())
            assertEquals(listOf("s-1"), playlists.orderedTrackIds(playlistId))
        }

    @Test
    fun `fallback does not refetch an album whose listed duration is above its song total`() =
        runBlocking(Dispatchers.IO) {
            server.advertised = listOf("apiKeyAuthentication")
            server.enqueueJson(ARTISTS)
            server.enqueueJson(ALBUM_LIST_ONE)
            server.enqueueJson(EMPTY_ALBUM_LIST)
            server.enqueueJson(ALBUM_FULL)
            repo.syncIncremental(server.client)

            // Navidrome lists 385 s for songs that add up to 380 s; the same album must cost no album fetch.
            server.enqueueJson(ARTISTS)
            server.enqueueJson(ALBUM_LIST_LONGER_DURATION)
            server.enqueueJson(EMPTY_ALBUM_LIST)
            val second = repo.syncIncremental(server.client)

            assertEquals(SyncMode.Unchanged, second.mode)
            assertEquals(0, second.rowsWritten)
        }

    @Test
    fun `fallback picks up a track added to an album when its song count changes`() =
        runBlocking(Dispatchers.IO) {
            server.advertised = listOf("apiKeyAuthentication")
            server.enqueueJson(ARTISTS)
            server.enqueueJson(ALBUM_LIST_ONE)
            server.enqueueJson(EMPTY_ALBUM_LIST)
            server.enqueueJson(ALBUM_FULL)
            repo.syncIncremental(server.client)
            assertEquals(2, db.trackDao().count())

            server.enqueueJson(ARTISTS)
            server.enqueueJson(ALBUM_LIST_THREE)
            server.enqueueJson(EMPTY_ALBUM_LIST)
            server.enqueueJson(ALBUM_THREE)
            val second = repo.syncIncremental(server.client)

            assertEquals(SyncMode.Changes, second.mode)
            assertEquals(3, db.trackDao().count())
            assertNotNull(db.trackDao().get("s-3"))
        }

    @Test
    fun `a tag-only edit rewrites only the changed song`() =
        runBlocking(Dispatchers.IO) {
            enqueueFirstSync()
            repo.syncIncremental(server.client)
            server.enqueueJson(INFO_NEW)
            server.enqueueJson(FEED_ALBUM_ONLY)
            server.enqueueJson(ALBUM_RETITLED_SONG)

            val result = repo.syncIncremental(server.client)

            assertEquals(1, result.rowsWritten)
            assertEquals("Uno", db.trackDao().get("s-1")!!.title)
        }

    @Test
    fun `a full listing is due when none ran or the last one is a week old`() =
        runBlocking(Dispatchers.IO) {
            val week = 7L * 24 * 60 * 60 * 1000
            assertTrue(repo.fullSyncDue(serverUrl(), week))
            enqueueFirstSync()
            repo.syncIncremental(server.client)

            assertFalse(repo.fullSyncDue(serverUrl(), week))
            val weekLater = LibraryRepository(db, NoDanceMetadataSource, clock = { 1_000L + week }, syncState = store)
            assertTrue(weekLater.fullSyncDue(serverUrl(), week))
        }

    /** Extensions, sync point, artists, two album pages and one album: what a first sync reads. */
    private fun enqueueFirstSync() {
        server.advertised = listOf("apiKeyAuthentication", "tinysonicLibraryChanges")
        server.enqueueJson(CHECKPOINT_AT_1000)
        server.enqueueJson(ARTISTS)
        server.enqueueJson(ALBUM_LIST_ONE)
        server.enqueueJson(EMPTY_ALBUM_LIST)
        server.enqueueSongsOf(ALBUM_FULL)
    }

    private fun serverUrl(): String = server.settings.baseUrl.toString()

    private companion object {
        const val FEED_ALBUM_ONLY =
            """{"subsonic-response":{"status":"ok","libraryChanges":{"full":false,"serverTime":2000,""" +
                """"artists":[],"albums":[{"id":"al-1","updated":1500}],"songs":[],"playlists":[],""" +
                """"starred":[],"deleted":[]}}}"""

        const val ALBUM_RETITLED_SONG =
            """{"subsonic-response":{"status":"ok","album":{"id":"al-1","name":"Night","artist":"Ada",""" +
                """"artistId":"ar-1","year":1999,"songCount":2,"duration":380,"song":[""" +
                """{"id":"s-1","title":"Uno","album":"Night","artist":"Ada","albumId":"al-1","track":1,""" +
                """"duration":200,"musicBrainzId":"rec-1"},""" +
                """{"id":"s-2","title":"Two","album":"Night","artist":"Ada","albumId":"al-1","track":2,""" +
                """"duration":180}]}}}"""

        const val CHECKPOINT_AT_1000 =
            """{"subsonic-response":{"status":"ok","libraryChanges":{"full":false,"serverTime":1000,""" +
                """"artists":[],"albums":[],"songs":[],"playlists":[],"starred":[],"deleted":[]}}}"""

        const val CHECKPOINT_AT_3000 =
            """{"subsonic-response":{"status":"ok","libraryChanges":{"full":false,"serverTime":3000,""" +
                """"artists":[],"albums":[],"songs":[],"playlists":[],"starred":[],"deleted":[]}}}"""

        /** Last modified in 1970: older than the stored point, so nothing changed. */
        const val INFO_OLD =
            """{"subsonic-response":{"status":"ok","tinysonicLibraryInfo":{"artists":1,"albums":1,"songs":2,""" +
                """"duration":380,"lastModified":"1970-01-01T00:00:00Z"}}}"""

        const val INFO_NEW =
            """{"subsonic-response":{"status":"ok","tinysonicLibraryInfo":{"artists":1,"albums":1,"songs":2,""" +
                """"duration":380,"lastModified":"2026-11-03T12:00:00Z"}}}"""

        const val FEED_ALBUM_AND_DELETED_SONG =
            """{"subsonic-response":{"status":"ok","libraryChanges":{"full":false,"serverTime":2000,""" +
                """"artists":[],"albums":[{"id":"al-1","updated":1500}],"songs":[],"playlists":[],""" +
                """"starred":[],"deleted":[{"type":"song","id":"s-2","deleted":1500}]}}}"""

        const val FEED_FULL =
            """{"subsonic-response":{"status":"ok","libraryChanges":{"full":true,"serverTime":3000,""" +
                """"artists":[],"albums":[],"songs":[],"playlists":[],"starred":[],"deleted":[]}}}"""

        const val ALBUM_LIST_THREE =
            """{"subsonic-response":{"status":"ok","albumList2":{"album":[""" +
                """{"id":"al-1","name":"Night","artistId":"ar-1","artist":"Ada","year":1999,""" +
                """"songCount":3,"duration":580}]}}}"""

        const val ALBUM_THREE =
            """{"subsonic-response":{"status":"ok","album":{"id":"al-1","name":"Night","artist":"Ada",""" +
                """"artistId":"ar-1","year":1999,"songCount":3,"duration":580,"song":[""" +
                """{"id":"s-1","title":"One","album":"Night","artist":"Ada","albumId":"al-1","track":1,""" +
                """"duration":200,"musicBrainzId":"rec-1"},""" +
                """{"id":"s-2","title":"Two","album":"Night","artist":"Ada","albumId":"al-1","track":2,""" +
                """"duration":180},""" +
                """{"id":"s-3","title":"Three","album":"Night","artist":"Ada","albumId":"al-1","track":3,""" +
                """"duration":200}]}}}"""

        const val ALBUM_LIST_LONGER_DURATION =
            """{"subsonic-response":{"status":"ok","albumList2":{"album":[""" +
                """{"id":"al-1","name":"Night","artistId":"ar-1","artist":"Ada","year":1999,""" +
                """"songCount":2,"duration":385}]}}}"""

        const val ARTISTS =
            """{"subsonic-response":{"status":"ok","artists":{"index":[{"name":"A","artist":[""" +
                """{"id":"ar-1","name":"Ada"}]}]}}}"""

        const val ALBUM_LIST_ONE =
            """{"subsonic-response":{"status":"ok","albumList2":{"album":[""" +
                """{"id":"al-1","name":"Night","artistId":"ar-1","artist":"Ada","year":1999,""" +
                """"songCount":2,"duration":380}]}}}"""

        const val EMPTY_ALBUM_LIST =
            """{"subsonic-response":{"status":"ok","albumList2":{"album":[]}}}"""

        const val ALBUM_FULL =
            """{"subsonic-response":{"status":"ok","album":{"id":"al-1","name":"Night","artist":"Ada",""" +
                """"artistId":"ar-1","year":1999,"songCount":2,"duration":380,"song":[""" +
                """{"id":"s-1","title":"One","album":"Night","artist":"Ada","albumId":"al-1","track":1,""" +
                """"duration":200,"musicBrainzId":"rec-1"},""" +
                """{"id":"s-2","title":"Two","album":"Night","artist":"Ada","albumId":"al-1","track":2,""" +
                """"duration":180}]}}}"""

        const val ALBUM_WITHOUT_SECOND_SONG =
            """{"subsonic-response":{"status":"ok","album":{"id":"al-1","name":"Night","artist":"Ada",""" +
                """"artistId":"ar-1","year":1999,"songCount":1,"duration":200,"song":[""" +
                """{"id":"s-1","title":"One","album":"Night","artist":"Ada","albumId":"al-1","track":1,""" +
                """"duration":200,"musicBrainzId":"rec-1"}]}}}"""

        /** Envelope error code 40 (wrong credentials): not transient, so not retried. */
        const val FAILED_NOT_RETRIED =
            """{"subsonic-response":{"status":"failed","error":{"code":40,"message":"boom"}}}"""

        /** Envelope error code 70: the album is gone. */
        const val NOT_FOUND =
            """{"subsonic-response":{"status":"failed","error":{"code":70,"message":"not found"}}}"""
    }
}
