package org.dancesonic.player.account

import org.dancesonic.player.subsonic.ServerSettings
import org.dancesonic.player.subsonic.ServerUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionUriTest {
    private val settings =
        ServerSettings(ServerUrl.parse("https://music.example.com/navidrome/"), "key with spaces+&=")
    private val danceUrl = ServerUrl.parse("https://dance.example.com/")

    @Test
    fun `round trip keeps the server address, the key and the DanceBrainz address`() {
        val parsed = ConnectionUri.parse(ConnectionUri.build(settings, danceUrl))

        assertEquals(settings.baseUrl, parsed.server.baseUrl)
        assertEquals(settings.apiKey, parsed.server.apiKey)
        assertEquals(danceUrl, parsed.danceBrainz)
    }

    @Test
    fun `a code without a DanceBrainz address still parses and names none`() {
        val code = "dancesonic://connect?server=https%3A%2F%2Fmusic.example.com%2F&key=key-123"

        val parsed = ConnectionUri.parse(code)

        assertEquals("key-123", parsed.server.apiKey)
        assertNull(parsed.danceBrainz)
    }

    @Test
    fun `plain http DanceBrainz address is rejected`() {
        val code =
            "dancesonic://connect?server=https%3A%2F%2Fmusic.example.com%2F&key=key-123" +
                "&dancebrainz=http%3A%2F%2Fdance.example.com%2F"

        assertFailsWithMessage("The DanceBrainz address must start with https://") {
            ConnectionUri.parse(code)
        }
    }

    @Test
    fun `built code has the connection scheme`() {
        assertTrue(ConnectionUri.isConnectionCode(ConnectionUri.build(settings, danceUrl)))
    }

    @Test
    fun `pairing code is not a connection code`() {
        val pairing = "tinysonic://pair?server=https%3A%2F%2Fmusic.example.com%2F&token=abcdefgh12345678"

        assertFalse(ConnectionUri.isConnectionCode(pairing))
        assertFailsWithMessage("This is not a connection code.") { ConnectionUri.parse(pairing) }
    }

    @Test
    fun `plain http server address is rejected`() {
        val code = "dancesonic://connect?server=http%3A%2F%2Fmusic.example.com%2F&key=key-123"

        assertFailsWithMessage(
            "The server address must start with https://, or http:// for a private network address",
        ) {
            ConnectionUri.parse(code)
        }
    }

    @Test
    fun `code without a key is rejected`() {
        val code = "dancesonic://connect?server=https%3A%2F%2Fmusic.example.com%2F"

        assertFailsWithMessage("The connection code has no API key.") { ConnectionUri.parse(code) }
    }

    @Test
    fun `code without a server is rejected`() {
        assertFailsWithMessage("The connection code has no server.") {
            ConnectionUri.parse("dancesonic://connect?key=key-123")
        }
    }

    private fun assertFailsWithMessage(
        expected: String,
        block: () -> Unit,
    ) {
        try {
            block()
        } catch (e: IllegalArgumentException) {
            assertEquals(expected, e.message)
            return
        }
        throw AssertionError("Expected IllegalArgumentException: $expected")
    }
}
