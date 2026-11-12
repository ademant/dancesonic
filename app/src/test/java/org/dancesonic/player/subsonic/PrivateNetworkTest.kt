package org.dancesonic.player.subsonic

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivateNetworkTest {
    @Test
    fun `loopback, RFC 1918 and link-local addresses are private`() {
        listOf(
            "localhost", "127.0.0.1", "10.0.2.2", "10.255.0.1", "172.16.0.1", "172.31.255.9", "192.168.1.20", "::1",
            "fd12:3456::1", "fe80::1",
        )
            .forEach { assertTrue(it, isPrivateNetworkHost(it)) }
    }

    @Test
    fun `public addresses and names are not private`() {
        listOf(
            "cloud.ademant.de",
            "8.8.8.8",
            "172.15.0.1",
            "172.32.0.1",
            "192.169.0.1",
            "11.0.0.1",
            "2001:db8::1",
            "192.168.1",
        )
            .forEach { assertFalse(it, isPrivateNetworkHost(it)) }
    }

    @Test
    fun `http is accepted for a private address`() {
        assertEquals("http://192.168.1.20/navidrome/", ServerUrl.parse("http://192.168.1.20/navidrome").toString())
    }

    @Test
    fun `http is rejected for a public address`() {
        assertThrows(IllegalArgumentException::class.java) { ServerUrl.parse("http://cloud.ademant.de/") }
        assertThrows(IllegalArgumentException::class.java) { ServerUrl.parse("http://8.8.8.8/") }
    }

    @Test
    fun `https is accepted for any address`() {
        assertTrue("https://8.8.8.8/".toHttpUrl().isAllowedServer())
    }

    @Test
    fun `settings accept an http private server and reject a public one`() {
        ServerSettings("http://10.0.2.2:4533/".toHttpUrl(), "key")
        assertThrows(IllegalArgumentException::class.java) { ServerSettings("http://example.com/".toHttpUrl(), "key") }
    }
}
