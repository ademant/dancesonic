package org.dancesonic.player.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.dancesonic.player.dance.DanceMetadataSource
import org.dancesonic.player.dance.NoDanceMetadataSource
import org.dancesonic.player.data.db.DanceSonicDatabase
import org.dancesonic.player.subsonic.SubsonicException
import org.dancesonic.player.testsupport.TestSubsonicServer
import org.junit.After
import org.junit.Assert.assertEquals
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
        db = Room.inMemoryDatabaseBuilder(
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
    fun `sync stores artists albums and tracks with their MBIDs`() = runBlocking(Dispatchers.IO) {
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
    fun `tracks missing from the server are removed on the next sync`() = runBlocking(Dispatchers.IO) {
        enqueueSmallLibrary()
        val repo = LibraryRepository(db, NoDanceMetadataSource, clock = { 1_000L })
        repo.sync(server.client)

        // Second run: the album no longer contains s-2.
        server.enqueueJson(ARTISTS)
        server.enqueueJson(ARTIST_AR1)
        server.enqueueJson(ALBUM_WITHOUT_SECOND_SONG)
        LibraryRepository(db, NoDanceMetadataSource, clock = { 2_000L }).sync(server.client)

        assertNull(db.trackDao().get("s-2"))
        assertEquals(1, db.trackDao().allMbids().size)
    }

    @Test
    fun `a failed sync leaves the previous catalog untouched`() = runBlocking(Dispatchers.IO) {
        enqueueSmallLibrary()
        LibraryRepository(db, NoDanceMetadataSource, clock = { 1_000L }).sync(server.client)

        server.enqueueJson(ARTISTS)
        server.enqueueError(500) // artist request fails mid-sync
        val repo = LibraryRepository(db, NoDanceMetadataSource, clock = { 2_000L })

        assertThrows(SubsonicException::class.java) {
            runBlocking(Dispatchers.IO) { repo.sync(server.client) }
        }
        assertEquals("Ada", db.artistDao().get("ar-1")!!.name)
        assertEquals(2, db.trackDao().getAll(listOf("s-1", "s-2")).size)
    }

    @Test
    fun `dance types from DanceBrainz are stored and shown per track`() = runBlocking(Dispatchers.IO) {
        enqueueSmallLibrary()
        val source = object : DanceMetadataSource {
            override suspend fun danceTypesFor(mbid: String): List<String>? =
                if (mbid == "rec-mbid-1") listOf("Waltz") else null
        }
        val repo = LibraryRepository(db, source, clock = { 1_000L })

        val result = repo.sync(server.client)

        assertEquals(0, result.danceLookupsFailed)
        val waltzTracks = repo.tracksForDance("Waltz").first()
        assertEquals(listOf("s-1"), waltzTracks.map { it.id })
        assertEquals(listOf("Waltz" to 1), repo.danceTypes().first().map { it.danceType to it.trackCount })
    }

    @Test
    fun `a failing DanceBrainz lookup is counted and keeps the track in the Library`() = runBlocking(Dispatchers.IO) {
        enqueueSmallLibrary()
        val failing = object : DanceMetadataSource {
            override suspend fun danceTypesFor(mbid: String): List<String>? = error("network down")
        }
        val repo = LibraryRepository(db, failing, clock = { 1_000L })

        val result = repo.sync(server.client)

        assertEquals(1, result.danceLookupsFailed)
        assertEquals(2, repo.tracks(listOf("s-1", "s-2")).size)
        assertEquals(emptyList<String>(), repo.danceTypes().first().map { it.danceType })
    }

    private fun enqueueSmallLibrary() {
        server.enqueueJson(ARTISTS)
        server.enqueueJson(ARTIST_AR1)
        server.enqueueJson(ALBUM_AL1)
    }

    private companion object {
        val ARTISTS = """{"subsonic-response":{"status":"ok","artists":{"index":[
            {"name":"A","artist":[{"id":"ar-1","name":"Ada"}]}]}}}"""

        val ARTIST_AR1 = """{"subsonic-response":{"status":"ok","artist":
            {"id":"ar-1","name":"Ada","album":[{"id":"al-1","name":"Night","year":1999}]}}}"""

        val ALBUM_AL1 = """{"subsonic-response":{"status":"ok","album":{"id":"al-1","name":"Night",
            "artist":"Ada","year":1999,"song":[
              {"id":"s-1","title":"One","track":1,"duration":200,"musicBrainzId":"rec-mbid-1"},
              {"id":"s-2","title":"Two","track":2,"duration":180}
            ]}}}"""

        val ALBUM_WITHOUT_SECOND_SONG = """{"subsonic-response":{"status":"ok","album":{"id":"al-1",
            "name":"Night","artist":"Ada","year":1999,"song":[
              {"id":"s-1","title":"One","track":1,"duration":200,"musicBrainzId":"rec-mbid-1"}
            ]}}}"""
    }
}
