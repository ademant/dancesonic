package org.dancesonic.player.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.RecordedRequest
import org.dancesonic.player.dance.DanceMetadataSource
import org.dancesonic.player.dance.NoDanceMetadataSource
import org.dancesonic.player.dance.RecordingDance
import org.dancesonic.player.data.db.DanceSonicDatabase
import org.dancesonic.player.subsonic.SubsonicException
import org.dancesonic.player.testsupport.TestSubsonicServer
import org.dancesonic.player.testsupport.songPageOf
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LibraryRepositoryTest {
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
    fun `sync stores artists albums and tracks with their MBIDs`() =
        runBlocking(Dispatchers.IO) {
            enqueueSmallLibrary()
            val repo = LibraryRepository(db, NoDanceMetadataSource, clock = { 1_000L })

            val result = repo.sync(server.client)

            assertEquals(1, result.artists)
            assertEquals(1, result.albums)
            assertEquals(2, result.tracks)
            assertEquals("rec-mbid-1", db.trackDao().get("s-1")!!.mbid)
            assertNull(db.trackDao().get("s-2")!!.mbid)
        }

    @Test
    fun `tracks missing from the server are removed on the next sync`() =
        runBlocking(Dispatchers.IO) {
            enqueueSmallLibrary()
            val repo = LibraryRepository(db, NoDanceMetadataSource, clock = { 1_000L })
            repo.sync(server.client)

            // Second run: the album no longer contains s-2.
            server.enqueueJson(ARTISTS)
            server.enqueueJson(ALBUM_LIST_ONE)
            server.enqueueJson(EMPTY_ALBUM_LIST)
            server.enqueueSongsOf(ALBUM_WITHOUT_SECOND_SONG)
            LibraryRepository(db, NoDanceMetadataSource, clock = { 2_000L }).sync(server.client)

            assertNull(db.trackDao().get("s-2"))
            assertEquals(1, db.trackDao().allMbids().size)
        }

    @Test
    fun `a failed sync leaves the previous catalog untouched`() =
        runBlocking(Dispatchers.IO) {
            enqueueSmallLibrary()
            LibraryRepository(db, NoDanceMetadataSource, clock = { 1_000L }).sync(server.client)

            server.enqueueJson(ARTISTS)
            server.enqueueJson(ALBUM_LIST_ONE)
            server.enqueueJson(EMPTY_ALBUM_LIST)
            server.enqueueError(500) // song listing fails mid-sync
            val repo = LibraryRepository(db, NoDanceMetadataSource, clock = { 2_000L })

            assertThrows(SubsonicException::class.java) {
                runBlocking(Dispatchers.IO) { repo.sync(server.client) }
            }
            assertEquals("Ada", db.artistDao().get("ar-1")!!.name)
            assertEquals(2, db.trackDao().getAll(listOf("s-1", "s-2")).size)
        }

    @Test
    fun `dance types from DanceBrainz are stored and shown per track`() =
        runBlocking(Dispatchers.IO) {
            enqueueSmallLibrary()
            val source =
                object : DanceMetadataSource {
                    override suspend fun lookup(mbids: List<String>): Map<String, RecordingDance> =
                        if ("rec-mbid-1" in mbids) {
                            mapOf(
                                "rec-mbid-1" to RecordingDance(listOf("Waltz"), null, null),
                            )
                        } else {
                            emptyMap()
                        }
                }
            val repo = LibraryRepository(db, source, clock = { 1_000L })

            val result = repo.sync(server.client)

            assertEquals(0, result.danceLookupsFailed)
            val waltzTracks = repo.tracksForDance("Waltz").first()
            assertEquals(listOf("s-1"), waltzTracks.map { it.id })
            assertEquals(listOf("Waltz" to 1), repo.danceTypes().first().map { it.danceType to it.trackCount })
        }

    @Test
    fun `a failing DanceBrainz lookup is counted and keeps the track in the Library`() =
        runBlocking(Dispatchers.IO) {
            enqueueSmallLibrary()
            val failing =
                object : DanceMetadataSource {
                    override suspend fun lookup(mbids: List<String>): Map<String, RecordingDance> =
                        error("network down")
                }
            val repo = LibraryRepository(db, failing, clock = { 1_000L })

            val result = repo.sync(server.client)

            assertEquals(1, result.danceLookupsFailed)
            assertEquals(2, repo.tracks(listOf("s-1", "s-2")).size)
            assertEquals(emptyList<String>(), repo.danceTypes().first().map { it.danceType })
        }

    @Test
    fun `albums whose artist is not in the index are skipped and counted`() =
        runBlocking(Dispatchers.IO) {
            server.enqueueJson(ARTISTS)
            server.enqueueJson(
                """{"subsonic-response":{"status":"ok","albumList2":{"album":[
              {"id":"al-1","name":"Night","artist":"Ada","artistId":"ar-1"},
              {"id":"al-9","name":"Orphan","artist":"Ghost","artistId":"ar-missing"}]}}}""",
            )
            server.enqueueJson(EMPTY_ALBUM_LIST)
            server.enqueueSongsOf(ALBUM_AL1)
            val repo = LibraryRepository(db, NoDanceMetadataSource, clock = { 1_000L })

            val result = repo.sync(server.client)

            assertEquals(1, result.albums)
            assertEquals(1, result.skippedAlbums)
            assertNull(db.albumDao().get("al-9"))
        }

    @Test
    fun `clearing the catalog keeps playlists and their entries`() =
        runBlocking(Dispatchers.IO) {
            enqueueSmallLibrary()
            val repo = LibraryRepository(db, NoDanceMetadataSource, clock = { 1_000L })
            repo.sync(server.client)
            val playlists = PlaylistRepository(db, clock = { 1_000L })
            val playlistId = playlists.create("Waltz course")
            playlists.addTrack(playlistId, "s-1", "Waltz")

            repo.clearCatalog()

            assertNull(db.trackDao().get("s-1"))
            assertNull(db.artistDao().get("ar-1"))
            assertNotNull(db.playlistDao().get(playlistId))
            assertEquals(listOf("s-1"), playlists.orderedTrackIds(playlistId))
        }

    @Test
    fun `sync reports progress with no count, since the number of albums is known only after the songs`() =
        runBlocking(Dispatchers.IO) {
            enqueueSmallLibrary()
            val reports = mutableListOf<Pair<Int, Int>>()
            val repo = LibraryRepository(db, NoDanceMetadataSource, clock = { 1_000L })

            repo.sync(server.client) { done, total -> synchronized(reports) { reports += done to total } }

            assertEquals(listOf(0 to 0), reports)
        }

    @Test
    fun `re-sync does not delete catalog rows`() =
        runBlocking(Dispatchers.IO) {
            // Regression for #13: REPLACE deleted parent rows, and the foreign-key cascade removed
            // their children, so every sync deleted and reinserted the whole catalog.
            enqueueSmallLibrary()
            val repo = LibraryRepository(db, NoDanceMetadataSource, clock = { 1_000L })
            repo.sync(server.client)

            val writable = db.openHelper.writableDatabase
            writable.execSQL("CREATE TABLE delete_log (tbl TEXT NOT NULL)")
            for (table in listOf("artist", "album", "track")) {
                writable.execSQL(
                    "CREATE TRIGGER log_${table}_delete AFTER DELETE ON $table " +
                        "BEGIN INSERT INTO delete_log (tbl) VALUES ('$table'); END",
                )
            }

            enqueueSmallLibrary()
            repo.sync(server.client)

            val deleted =
                db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM delete_log").use {
                    it.moveToFirst()
                    it.getInt(0)
                }
            assertEquals(0, deleted)
            assertEquals(2, db.trackDao().getAll(listOf("s-1", "s-2")).size)
        }

    @Test
    fun `sync finishes with more artists than concurrent requests`() =
        runBlocking(Dispatchers.IO) {
            // Regression: artist tasks used to hold a permit while waiting for their album tasks,
            // which needed permits too. With more artists than permits, the sync never finished.
            val artists = 6
            server.server.dispatcher =
                object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse {
                        val url = request.requestUrl!!
                        val body =
                            when (url.encodedPath) {
                                "/rest/getArtists.view" -> artistIndex(artists)
                                "/rest/getAlbumList2.view" ->
                                    if (url.queryParameter("offset") == "0") {
                                        albumList(artists)
                                    } else {
                                        EMPTY_ALBUM_LIST
                                    }
                                "/rest/search3.view" ->
                                    if (url.queryParameter("songOffset") == "0") {
                                        songPageOf(*(1..artists).map { albumWithOneSong("al-$it") }.toTypedArray())
                                    } else {
                                        songPageOf()
                                    }
                                "/rest/getOpenSubsonicExtensions.view" ->
                                    """{"subsonic-response":{"status":"ok","openSubsonicExtensions":[""" +
                                        """{"name":"apiKeyAuthentication","versions":[1]}]}}"""
                                else -> return MockResponse().setResponseCode(404)
                            }
                        return MockResponse().addHeader("Content-Type", "application/json").setBody(body)
                    }
                }
            val repo = LibraryRepository(db, NoDanceMetadataSource, clock = { 1_000L })

            val result = withTimeout(10_000) { repo.sync(server.client) }

            assertEquals(artists, result.artists)
            assertEquals(artists, result.albums)
            assertEquals(artists, result.tracks)
        }

    private fun artistIndex(count: Int): String {
        val entries = (1..count).joinToString(",") { """{"id":"ar-$it","name":"Artist $it"}""" }
        return """{"subsonic-response":{"status":"ok","artists":{"index":[{"name":"A","artist":[$entries]}]}}}"""
    }

    private fun albumList(count: Int): String {
        val entries =
            (1..count).joinToString(",") {
                """{"id":"al-$it","name":"Album $it","artist":"Artist $it","artistId":"ar-$it"}"""
            }
        return """{"subsonic-response":{"status":"ok","albumList2":{"album":[$entries]}}}"""
    }

    private fun albumWithOneSong(albumId: String): String {
        val n = albumId.removePrefix("al-")
        return """{"subsonic-response":{"status":"ok","album":{"id":"$albumId","name":"Album $n",
            "artist":"Artist $n","artistId":"ar-$n",
            "song":[{"id":"s-$n","title":"Song $n","track":1,"duration":100}]}}}"""
    }

    @Test
    fun `re-sync of an unchanged server writes no rows`() =
        runBlocking(Dispatchers.IO) {
            enqueueSmallLibrary()
            val repo = LibraryRepository(db, NoDanceMetadataSource, clock = { 1_000L })
            repo.sync(server.client)
            enqueueSmallLibrary()

            val result = repo.sync(server.client)

            assertEquals(0, result.rowsWritten)
            assertEquals(2, result.tracks)
        }

    @Test
    fun `a changed song is the only row written`() =
        runBlocking(Dispatchers.IO) {
            enqueueSmallLibrary()
            val repo = LibraryRepository(db, NoDanceMetadataSource, clock = { 1_000L })
            repo.sync(server.client)
            server.enqueueJson(ARTISTS)
            server.enqueueJson(ALBUM_LIST_ONE)
            server.enqueueJson(EMPTY_ALBUM_LIST)
            server.enqueueSongsOf(ALBUM_AL1.replace("\"Two\"", "\"Two (live)\""))

            val result = repo.sync(server.client)

            assertEquals(1, result.rowsWritten)
            assertEquals("Two (live)", db.trackDao().get("s-2")?.title)
        }

    @Test
    fun `a song removed from the server is the only row removed`() =
        runBlocking(Dispatchers.IO) {
            enqueueSmallLibrary()
            val repo = LibraryRepository(db, NoDanceMetadataSource, clock = { 1_000L })
            repo.sync(server.client)
            server.enqueueJson(ARTISTS)
            server.enqueueJson(ALBUM_LIST_ONE)
            server.enqueueJson(EMPTY_ALBUM_LIST)
            server.enqueueSongsOf(ALBUM_WITHOUT_SECOND_SONG)

            val result = repo.sync(server.client)

            assertEquals(1, result.rowsWritten)
            assertNull(db.trackDao().get("s-2"))
        }

    @Test
    fun `an album with more songs than one write batch is stored in full`() =
        runBlocking(Dispatchers.IO) {
            val songs =
                (1..1201).joinToString(
                    ",",
                ) { """{"id":"s-$it","title":"Song $it","track":$it,"duration":60}""" }
            server.enqueueJson(ARTISTS)
            server.enqueueJson(ALBUM_LIST_ONE)
            server.enqueueJson(EMPTY_ALBUM_LIST)
            server.enqueueSongsOf(
                """{"subsonic-response":{"status":"ok","album":{"id":"al-1","name":"Night","artist":"Ada",""" +
                    """"artistId":"ar-1","song":[$songs]}}}""",
            )
            val repo = LibraryRepository(db, NoDanceMetadataSource, clock = { 1_000L })

            val result = repo.sync(server.client)

            assertEquals(1201, result.tracks)
            assertEquals(1201, db.trackDao().all().size)
            assertEquals(1203, result.rowsWritten)
        }

    private fun enqueueSmallLibrary() {
        server.enqueueJson(ARTISTS)
        server.enqueueJson(ALBUM_LIST_ONE)
        server.enqueueJson(EMPTY_ALBUM_LIST)
        server.enqueueSongsOf(ALBUM_AL1)
    }

    private companion object {
        const val ARTISTS = """{"subsonic-response":{"status":"ok","artists":{"index":[
            {"name":"A","artist":[{"id":"ar-1","name":"Ada"}]}]}}}"""

        const val ALBUM_LIST_ONE = """{"subsonic-response":{"status":"ok","albumList2":{"album":[
            {"id":"al-1","name":"Night","artist":"Ada","artistId":"ar-1","year":1999}]}}}"""

        const val EMPTY_ALBUM_LIST = """{"subsonic-response":{"status":"ok","albumList2":{"album":[]}}}"""

        const val ALBUM_AL1 = """{"subsonic-response":{"status":"ok","album":{"id":"al-1","name":"Night",
            "artist":"Ada","year":1999,"song":[
              {"id":"s-1","title":"One","track":1,"duration":200,"musicBrainzId":"rec-mbid-1"},
              {"id":"s-2","title":"Two","track":2,"duration":180}
            ]}}}"""

        const val ALBUM_WITHOUT_SECOND_SONG = """{"subsonic-response":{"status":"ok","album":{"id":"al-1",
            "name":"Night","artist":"Ada","year":1999,"song":[
              {"id":"s-1","title":"One","track":1,"duration":200,"musicBrainzId":"rec-mbid-1"}
            ]}}}"""
    }
}
