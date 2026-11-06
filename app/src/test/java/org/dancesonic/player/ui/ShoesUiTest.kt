package org.dancesonic.player.ui

import org.dancesonic.player.data.ShoesView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ShoesUiTest {
    @Test
    fun `a track with no shoes state shows no summary`() {
        assertNull(shoesSummary(null))
    }

    @Test
    fun `the summary shows the viewer's rating, the median and the count, with a pending change marked`() {
        val view = ShoesView(mine = 4, median = 3.25, raters = 12, pending = true, error = "offline")

        assertEquals("Shoes 4 · median 3.3 (12) · not sent yet", shoesSummary(view))
    }

    @Test
    fun `a track with only a median shows the median`() {
        val view = ShoesView(mine = null, median = 2.0, raters = 3, pending = false, error = null)

        assertEquals("median 2.0 (3)", shoesSummary(view))
    }

    @Test
    fun `a track without an MBID says why it cannot be rated`() {
        assertEquals("Cannot be edited: no MusicBrainz ID", shoesReason(mbid = null, signedIn = true))
    }

    @Test
    fun `a signed-out viewer is told to sign in`() {
        assertEquals(
            "Sign in to DanceBrainz to edit",
            shoesReason(mbid = "7f9c2a1e-0d3b-4c5a-9e8f-1a2b3c4d5e6f", signedIn = false),
        )
    }

    @Test
    fun `a signed-in viewer can edit a track with an MBID`() {
        assertNull(shoesReason(mbid = "7f9c2a1e-0d3b-4c5a-9e8f-1a2b3c4d5e6f", signedIn = true))
    }
}
