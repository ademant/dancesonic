package org.dancesonic.player.subsonic

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class CoverArtUrlTest {
    private val settings =
        ServerSettings(baseUrl = "https://music.example.org/navidrome/".toHttpUrl(), apiKey = "secret-key-123")

    @Test
    fun `the cover URL asks for the album at the given size`() {
        val url = settings.coverArtUrl("album-7", size = 512)

        assertEquals("/navidrome/rest/getCoverArt.view", url.encodedPath)
        assertEquals("album-7", url.queryParameter("id"))
        assertEquals("512", url.queryParameter("size"))
    }

    @Test
    fun `the cover URL carries no API key, which the shared client adds`() {
        val url = settings.coverArtUrl("album-7", size = 128).toString()

        assertFalse(url.contains("secret-key-123"))
        assertFalse(url.contains("apiKey"))
    }
}
