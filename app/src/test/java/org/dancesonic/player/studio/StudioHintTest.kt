package org.dancesonic.player.studio

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StudioHintTest {
    @Test
    fun `private and link-local IPv4 addresses are used, in Studio's order`() {
        val urls = StudioHint.urls(listOf("192.168.1.20", "10.0.0.5", "169.254.3.4", "172.16.0.9"), 47800)

        assertEquals(
            listOf(
                "https://192.168.1.20:47800/".toHttpUrl(),
                "https://10.0.0.5:47800/".toHttpUrl(),
                "https://169.254.3.4:47800/".toHttpUrl(),
                "https://172.16.0.9:47800/".toHttpUrl(),
            ),
            urls,
        )
    }

    @Test
    fun `public addresses are never dialled`() {
        assertTrue(StudioHint.urls(listOf("8.8.8.8", "172.32.0.1", "192.169.0.1"), 47800).isEmpty())
        assertFalse(StudioHint.isPrivateAddress("203.0.113.7"))
    }

    @Test
    fun `host names are refused, so no lookup happens`() {
        assertTrue(StudioHint.urls(listOf("studio.example.com", "localhost"), 47800).isEmpty())
    }

    @Test
    fun `unique-local and link-local IPv6 are used, global ones are not`() {
        assertTrue(StudioHint.isPrivateAddress("fd12:3456::1"))
        assertTrue(StudioHint.isPrivateAddress("fe80::1"))
        assertFalse(StudioHint.isPrivateAddress("2001:db8::1"))
    }

    @Test
    fun `a port outside 1 to 65535 gives no address`() {
        assertTrue(StudioHint.urls(listOf("192.168.1.2"), 0).isEmpty())
        assertTrue(StudioHint.urls(listOf("192.168.1.2"), 70000).isEmpty())
    }

    @Test
    fun `mDNS is tried first, then the hinted addresses`() {
        val found = "192.168.1.9".let { "https://$it:47800/".toHttpUrl() }

        val candidates = StudioHint.candidates(found, listOf("10.0.0.5"), 47800)

        assertEquals(listOf(found, "https://10.0.0.5:47800/".toHttpUrl()), candidates)
    }

    @Test
    fun `without mDNS only the hinted addresses are tried, and without a hint nothing`() {
        assertEquals(
            listOf("https://10.0.0.5:47800/".toHttpUrl()),
            StudioHint.candidates(null, listOf("10.0.0.5"), 47800),
        )
        assertTrue(StudioHint.candidates(null, emptyList(), null).isEmpty())
    }
}
