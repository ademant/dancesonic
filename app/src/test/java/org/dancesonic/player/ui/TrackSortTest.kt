package org.dancesonic.player.ui

import org.dancesonic.player.data.db.TrackEntity
import org.dancesonic.player.data.db.TrackWithTempo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackSortTest {
    private fun row(
        id: String,
        durationSec: Int?,
    ) = TrackWithTempo(
        track =
            TrackEntity(
                id = id,
                albumId = "album",
                artistName = "Artist",
                title = id,
                trackNumber = null,
                durationSec = durationSec,
                mbid = null,
                syncedAt = 0,
            ),
        tempoBpm = null,
    )

    private val tracks =
        listOf(
            row("a", 200),
            row("b", null),
            row("c", 90),
            row("d", 200),
            row("e", 300),
        )

    private fun ids(sorted: List<TrackWithTempo>) = sorted.map { it.track.id }

    @Test
    fun `the default keeps the order the library gives`() {
        assertEquals(listOf("a", "b", "c", "d", "e"), ids(sortTracks(tracks, TrackSort.ARTIST_TITLE)))
    }

    @Test
    fun `length ascending puts the shortest first and unknown lengths last`() {
        assertEquals(listOf("c", "a", "d", "e", "b"), ids(sortTracks(tracks, TrackSort.LENGTH_ASC)))
    }

    @Test
    fun `length descending puts the longest first and unknown lengths still last`() {
        assertEquals(listOf("e", "a", "d", "c", "b"), ids(sortTracks(tracks, TrackSort.LENGTH_DESC)))
    }

    @Test
    fun `tracks of the same length keep their library order in both directions`() {
        // a and d both last 200 seconds and a comes first in the library, so a stays before d in both directions.
        val asc = ids(sortTracks(tracks, TrackSort.LENGTH_ASC))
        val desc = ids(sortTracks(tracks, TrackSort.LENGTH_DESC))
        assertTrue(asc.indexOf("a") < asc.indexOf("d"))
        assertTrue(desc.indexOf("a") < desc.indexOf("d"))
    }

    @Test
    fun `sorting does not change the list it was given`() {
        sortTracks(tracks, TrackSort.LENGTH_ASC)
        assertEquals(listOf("a", "b", "c", "d", "e"), ids(tracks))
    }

    @Test
    fun `randomize keeps every track and gives the same order for the same seed`() {
        val shuffled = ids(sortTracks(tracks, TrackSort.RANDOM, seed = 7L))

        assertEquals(listOf("a", "b", "c", "d", "e"), shuffled.sorted())
        assertEquals(shuffled, ids(sortTracks(tracks, TrackSort.RANDOM, seed = 7L)))
    }

    @Test
    fun `a new seed deals a new order`() {
        val orders = (1L..10L).map { ids(sortTracks(tracks, TrackSort.RANDOM, seed = it)) }.toSet()

        assertTrue("ten seeds gave only ${orders.size} orders", orders.size > 1)
    }
}
