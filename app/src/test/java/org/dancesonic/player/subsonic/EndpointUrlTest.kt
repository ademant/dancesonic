package org.dancesonic.player.subsonic

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

/** The fixed endpoint URLs (#86): built without non-null assertions, and failing with a safe message. */
class EndpointUrlTest {
    private val base = "https://music.example.org/navidrome/".toHttpUrl()
    private val settings = ServerSettings(baseUrl = base, apiKey = "secret-key-123")

    @Test
    fun `the stream URL names the song under the server's rest path`() {
        val url = settings.streamUrl("song-1")

        assertEquals("https", url.scheme)
        assertEquals("music.example.org", url.host)
        assertEquals("/navidrome/rest/stream.view", url.encodedPath)
        assertEquals("song-1", url.queryParameter("id"))
        assertEquals(listOf("id"), url.queryParameterNames.toList())
    }

    @Test
    fun `the cover URL names the album and size under the server's rest path`() {
        val url = settings.coverArtUrl("album-7", size = 256)

        assertEquals("/navidrome/rest/getCoverArt.view", url.encodedPath)
        assertEquals("album-7", url.queryParameter("id"))
        assertEquals("256", url.queryParameter("size"))
    }

    @Test
    fun `a song id with URL characters is encoded, not resolved as a path`() {
        val url = settings.streamUrl("a/b?c=d")

        assertEquals("/navidrome/rest/stream.view", url.encodedPath)
        assertEquals("a/b?c=d", url.queryParameter("id"))
    }

    @Test
    fun `an endpoint that cannot be resolved fails with a message that leaves out the server`() {
        val failure = assertThrows(IllegalStateException::class.java) { base.endpoint("mailto:nobody") }

        val message = failure.message.orEmpty()
        assertEquals("Could not build the server address for mailto:nobody", message)
        assertFalse(message.contains("music.example.org"))
    }
}
