package org.dancesonic.player.data

import org.dancesonic.player.data.db.PlaylistItemRow
import org.junit.Assert.assertEquals
import org.junit.Test

class PlaylistGroupingTest {

    private fun row(id: Long, danceType: String?, position: Int) =
        PlaylistItemRow(
            id = id,
            playlistId = 1,
            trackId = "t$id",
            danceType = danceType,
            position = position,
            title = "Track $id",
            artistName = "Artist",
        )

    @Test
    fun `groups are sorted by dance type case-insensitively with ungrouped last`() {
        val groups = PlaylistRepository.groupByDanceType(
            listOf(
                row(1, null, 0),
                row(2, "waltz", 1),
                row(3, "Foxtrot", 2),
                row(4, "Waltz", 3),
            ),
        )

        assertEquals(listOf("Foxtrot", "waltz", null), groups.map { it.danceType })
        assertEquals(listOf(2L, 4L), groups[1].items.map { it.id })
        assertEquals(listOf(1L), groups.last().items.map { it.id })
    }

    @Test
    fun `items inside a group keep their playlist position`() {
        val groups = PlaylistRepository.groupByDanceType(
            listOf(row(9, "Rumba", 5), row(7, "Rumba", 2), row(8, "Rumba", 4)),
        )

        assertEquals(listOf(7L, 8L, 9L), groups.single().items.map { it.id })
    }

    @Test
    fun `no ungrouped group when every item has a dance type`() {
        val groups = PlaylistRepository.groupByDanceType(listOf(row(1, "Tango", 0)))
        assertEquals(listOf<String?>("Tango"), groups.map { it.danceType })
    }
}
