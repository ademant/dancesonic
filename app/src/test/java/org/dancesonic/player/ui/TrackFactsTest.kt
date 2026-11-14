package org.dancesonic.player.ui

import org.dancesonic.player.data.ShoesView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TrackFactsTest {
    @Test
    fun `a length under an hour is m-ss and from an hour is h-mm-ss`() {
        assertEquals("3:45", formatLength(225))
        assertEquals("0:05", formatLength(5))
        assertEquals("1:02:05", formatLength(3725))
    }

    @Test
    fun `the public shoes median is shown with the number of raters`() {
        val view = ShoesView(mine = null, median = 3.5, raters = 12, pending = false, error = null)

        assertEquals("Median 3.5 from 12 raters", shoesMedianText(view))
    }

    @Test
    fun `without a median there is no median line`() {
        assertNull(shoesMedianText(ShoesView(mine = 2, median = null, raters = 0, pending = false, error = null)))
        assertNull(shoesMedianText(null))
    }
}
