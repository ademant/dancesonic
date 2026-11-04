package org.dancesonic.player.subsonic

import kotlinx.coroutines.runBlocking
import org.dancesonic.player.testsupport.TestSubsonicServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class FolksonicFeedTest {
    private lateinit var fake: TestSubsonicServer
    private lateinit var client: SubsonicClient

    @Before
    fun setUp() {
        fake = TestSubsonicServer()
        client = SubsonicClient(fake.settings, SubsonicHttp.service(fake.settings.baseUrl, fake.http), 1L)
    }

    @After
    fun tearDown() {
        fake.close()
    }

    @Test
    fun `library info reports totals and the last change`() =
        runBlocking {
            fake.enqueueJson(INFO)

            val info = client.libraryInfo()

            assertEquals(2, info.artists)
            assertEquals("2026-11-03T12:00:00Z", info.lastModified)
            assertEquals("/rest/tinysonicLibraryInfo.view", fake.server.takeRequest().requestUrl!!.encodedPath)
        }

    @Test
    fun `change feed pages are read as one snapshot, first page with since, next pages with the cursor`() =
        runBlocking {
            fake.enqueueJson(CHANGES_PAGE_1)
            fake.enqueueJson(CHANGES_PAGE_2)

            val changes = client.libraryChanges(since = 500L)

            val first = fake.server.takeRequest().requestUrl!!
            assertEquals("500", first.queryParameter("since"))
            assertEquals("500", first.queryParameter("size"))
            assertNull(first.queryParameter("cursor"))
            val second = fake.server.takeRequest().requestUrl!!
            assertEquals("c1", second.queryParameter("cursor"))
            assertNull(second.queryParameter("since"))

            assertEquals(1000L, changes.serverTime)
            assertEquals(listOf("ar-1"), changes.artists.map { it.id })
            assertEquals(listOf("al-1", "al-2"), changes.albums.map { it.id })
            assertEquals(listOf("s-1"), changes.songs.map { it.id })
            assertEquals(listOf("s-9"), changes.deleted.map { it.id })
            assertFalse(changes.full)
        }

    @Test
    fun `a request without since asks for the whole library`() =
        runBlocking {
            fake.enqueueJson(FULL_EMPTY)

            val changes = client.libraryChanges(since = null)

            assertTrue(changes.full)
            assertNull(fake.server.takeRequest().requestUrl!!.queryParameter("since"))
        }

    @Test
    fun `song lookup returns the album id needed to refresh its album`() =
        runBlocking {
            fake.enqueueJson(SONG)

            val song = client.song("s-1")

            assertEquals("al-1", song.albumId)
        }

    @Test
    fun `album list entries carry song count and duration`() =
        runBlocking {
            fake.enqueueJson(ALBUM_STUB)

            val album = client.album("al-1")

            assertEquals(2, album.songCount)
            assertEquals(380, album.duration)
        }

    @Test
    fun `extension support is asked once per client`() =
        runBlocking {
            fake.enqueueJson(EXTENSIONS)

            assertTrue(client.supports("tinysonicLibraryChanges"))
            assertTrue(client.supports("apiKeyAuthentication"))
            assertFalse(client.supports("tinysonicMeter"))
            assertEquals(1, fake.server.requestCount)
        }

    private companion object {
        const val INFO =
            """{"subsonic-response":{"status":"ok","tinysonicLibraryInfo":{"artists":2,"albums":3,"songs":9,""" +
                """"duration":1200,"lastModified":"2026-11-03T12:00:00Z"}}}"""

        const val CHANGES_PAGE_1 =
            """{"subsonic-response":{"status":"ok","libraryChanges":{"full":false,"serverTime":1000,""" +
                """"artists":[{"id":"ar-1","updated":900}],"albums":[{"id":"al-1","updated":950}],"songs":[],""" +
                """"playlists":[{"id":"p-1","updated":1}],"starred":[],""" +
                """"deleted":[{"type":"song","id":"s-9","deleted":960}],"nextCursor":"c1"}}}"""

        const val CHANGES_PAGE_2 =
            """{"subsonic-response":{"status":"ok","libraryChanges":{"full":false,"serverTime":1000,""" +
                """"artists":[],"albums":[{"id":"al-2","updated":990}],"songs":[{"id":"s-1","updated":980}],""" +
                """"playlists":[],"starred":[],"deleted":[]}}}"""

        const val FULL_EMPTY =
            """{"subsonic-response":{"status":"ok","libraryChanges":{"full":true,"serverTime":1000,""" +
                """"artists":[],"albums":[],"songs":[],"playlists":[],"starred":[],"deleted":[]}}}"""

        const val SONG =
            """{"subsonic-response":{"status":"ok","song":{"id":"s-1","title":"One","album":"Night",""" +
                """"artist":"Ada","albumId":"al-1","track":1,"duration":200}}}"""

        const val ALBUM_STUB =
            """{"subsonic-response":{"status":"ok","album":{"id":"al-1","name":"Night","artist":"Ada",""" +
                """"artistId":"ar-1","year":1999,"songCount":2,"duration":380,"song":[]}}}"""

        const val EXTENSIONS =
            """{"subsonic-response":{"status":"ok","openSubsonicExtensions":[""" +
                """{"name":"apiKeyAuthentication","versions":[1]},""" +
                """{"name":"tinysonicLibraryChanges","versions":[1]}]}}"""
    }
}
