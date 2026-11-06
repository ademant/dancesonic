package org.dancesonic.player.ui

import org.dancesonic.player.playback.DownloadBlock
import org.dancesonic.player.playback.OfflineStatus
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Locale

class OfflineUiTest {
    // Sizes are formatted in the phone's locale (a German phone shows 2,6 MB), so the tests pin one.
    private val savedLocale = Locale.getDefault()

    @Before
    fun useDotDecimals() {
        Locale.setDefault(Locale.US)
    }

    @After
    fun restoreLocale() {
        Locale.setDefault(savedLocale)
    }

    @Test
    fun `sizes use decimal units from KB upward`() {
        assertEquals("999 B", formatBytes(999))
        assertEquals("1 KB", formatBytes(1_000))
        assertEquals("2.6 MB", formatBytes(2_600_000))
        assertEquals("1.5 GB", formatBytes(1_500_000_000))
    }

    @Test
    fun `an empty offline state says nothing is kept`() {
        val lines = offlineLines(OfflineStatus(cacheBytes = 0))

        assertEquals("Audio cache: 0 B in use, kept songs included.", lines[0])
        assertEquals("Nothing is kept offline.", lines[1])
        assertEquals(2, lines.size)
    }

    @Test
    fun `kept tracks show how many are on the phone`() {
        val lines =
            offlineLines(
                OfflineStatus(keptTracks = 10, heldTracks = 4, heldBytes = 12_000_000, cacheBytes = 20_000_000),
            )

        assertEquals("Kept: 4 of 10 tracks on the phone (12.0 MB).", lines[1])
    }

    @Test
    fun `unsized tracks and the reason downloads wait are reported`() {
        val lines =
            offlineLines(
                OfflineStatus(keptTracks = 1, unsizedTracks = 2, blocked = DownloadBlock.WaitingForWifi),
            )

        assertTrue(lines.any { it.startsWith("2 kept tracks have no size") })
        assertTrue(lines.last().startsWith("Downloads wait for Wi-Fi"))
    }

    @Test
    fun `an offline phone pauses downloads until it is back online`() {
        val lines = offlineLines(OfflineStatus(keptTracks = 1, blocked = DownloadBlock.Offline))

        assertTrue(lines.last().startsWith("Downloads are paused: the phone is offline"))
    }
}
