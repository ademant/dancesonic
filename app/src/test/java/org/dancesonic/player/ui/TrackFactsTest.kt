package org.dancesonic.player.ui

import org.dancesonic.player.data.ShoesView
import org.junit.Assert.assertEquals
import org.junit.Test

class TrackFactsTest {
    @Test
    fun `a length under an hour is m-ss and from an hour is h-mm-ss`() {
        assertEquals("3:45", formatLength(225))
        assertEquals("0:05", formatLength(5))
        assertEquals("1:02:05", formatLength(3725))
    }

    @Test
    fun `the own rating comes before the public median`() {
        val view = ShoesView(mine = 4, median = 3.5, raters = 12, pending = false, error = null)

        assertEquals("Shoes rating: 4 (yours)", shoesFacts(view, hasMbid = true))
    }

    @Test
    fun `without an own rating the public median is shown`() {
        val view = ShoesView(mine = null, median = 3.5, raters = 12, pending = false, error = null)

        assertEquals("Shoes rating: median 3.5 from 12 raters", shoesFacts(view, hasMbid = true))
    }

    @Test
    fun `a track without an MBID has no shoes rating, and a queued rating says it is not sent`() {
        assertEquals("No shoes rating: no MusicBrainz ID", shoesFacts(null, hasMbid = false))
        val queued = ShoesView(mine = 2, median = null, raters = 0, pending = true, error = null)
        assertEquals("Shoes rating: 2 (yours) · not sent yet", shoesFacts(queued, hasMbid = true))
    }
}
