package org.dancesonic.player.ui

import org.dancesonic.player.data.ShoesView
import org.dancesonic.player.data.SyncMode
import org.dancesonic.player.data.SyncResult
import org.junit.Assert.assertEquals
import org.junit.Test

/** Counts read as words: one of a thing is singular, which the Dance list once got wrong as `1 tracks`. */
class CountsTest {
    @Test
    fun `one takes the singular, every other count the plural`() {
        assertEquals("1 track", count(1, "track"))
        assertEquals("0 tracks", count(0, "track"))
        assertEquals("25 tracks", count(25, "track"))
    }

    @Test
    fun `a median from one rater says rater`() {
        val view = ShoesView(mine = null, median = 4.0, raters = 1, pending = false, error = null)

        assertEquals("Median 4.0 from 1 rater", shoesMedianText(view))
    }

    @Test
    fun `a sync summary counts each kind in words`() {
        val result =
            SyncResult(
                artists = 1,
                albums = 2,
                tracks = 1,
                danceLookupsFailed = 1,
                skippedAlbums = 0,
                rowsWritten = 1,
                mode = SyncMode.Full,
            )

        assertEquals("Synced 1 artist, 2 albums, 1 track, 1 dance lookup failed", result.summary())
        assertEquals("Applied 1 change, 1 dance lookup failed", result.copy(mode = SyncMode.Changes).summary())
    }
}
