package org.dancesonic.player.ui

import org.dancesonic.player.subsonic.ServerSettings
import org.dancesonic.player.subsonic.ServerUrl
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionChangeTest {
    private val first = ServerSettings(ServerUrl.parse("https://music.example.com/"), "key-one")

    @Test
    fun `first connection counts as a server change`() {
        assertTrue(connectionChangesServer(previous = null, next = first))
    }

    @Test
    fun `new key for the same server is not a server change`() {
        val rekeyed = ServerSettings(ServerUrl.parse("https://music.example.com/"), "key-two")

        assertFalse(connectionChangesServer(previous = first, next = rekeyed))
    }

    @Test
    fun `different server address is a server change`() {
        val other = ServerSettings(ServerUrl.parse("https://other.example.com/"), "key-one")

        assertTrue(connectionChangesServer(previous = first, next = other))
    }
}
