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
            "localhost",
            "127.0.0.1",
            "10.0.2.2",
            "10.255.0.1",
            "172.16.0.1",
            "172.31.255.9",
            "192.168.1.20",
            "::1",
            "fd12:3456::1",
            "fe80::1",
        ).forEach { assertTrue(it, isPrivateNetworkHost(it)) }
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
        ).forEach { assertFalse(it, isPrivateNetworkHost(it)) }
    }

    @Test
    fun `http is rejected by default, even for a private address (#84)`() {
        assertThrows(IllegalArgumentException::class.java) { ServerUrl.parse("http://192.168.1.20/navidrome") }
        assertFalse("http://192.168.1.20/".toHttpUrl().isAllowedServer(allowCleartext = false))
    }

    @Test
    fun `http is accepted for a private address once allowed`() {
        assertEquals(
            "http://192.168.1.20/navidrome/",
            ServerUrl.parse("http://192.168.1.20/navidrome", allowCleartext = true).toString(),
        )
    }

    @Test
    fun `http is rejected for a public address, allowed or not`() {
        listOf(false, true).forEach { allowed ->
            assertThrows(IllegalArgumentException::class.java) { ServerUrl.parse("http://cloud.ademant.de/", allowed) }
            assertThrows(IllegalArgumentException::class.java) { ServerUrl.parse("http://8.8.8.8/", allowed) }
        }
    }

    @Test
    fun `https is accepted for any address and needs no opt-in`() {
        assertTrue("https://8.8.8.8/".toHttpUrl().isAllowedServer(allowCleartext = false))
        assertEquals("https://192.168.1.20/", ServerUrl.parse("192.168.1.20").toString())
    }

    @Test
    fun `settings accept an http private server only with the opt-in, and never a public one`() {
        val emulatorHost = "http://10.0.2.2:4533/".toHttpUrl()
        assertThrows(IllegalArgumentException::class.java) { ServerSettings(emulatorHost, "key") }
        ServerSettings(emulatorHost, "key", allowCleartext = true)
        assertThrows(IllegalArgumentException::class.java) {
            ServerSettings("http://example.com/".toHttpUrl(), "key", allowCleartext = true)
        }
    }
}
