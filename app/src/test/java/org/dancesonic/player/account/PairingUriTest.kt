package org.dancesonic.player.account

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PairingUriTest {
    @Test
    fun `valid code yields server and token`() {
        val request =
            PairingUri.parse(
                "tinysonic://pair?server=https%3A%2F%2Fmusic.example.com%2F&token=tsp_abcdefgh12345",
            )
        assertEquals("https://music.example.com/", request.server.toString())
        assertEquals("tsp_abcdefgh12345", request.token)
    }

    @Test
    fun `subpath deployments keep their path`() {
        val request =
            PairingUri.parse(
                "tinysonic://pair?server=https%3A%2F%2Fexample.com%2Ffolksonic&token=tsp_abcdefgh12345",
            )
        assertEquals("https://example.com/folksonic/", request.server.toString())
    }

    @Test
    fun `other schemes are rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            PairingUri.parse("https://pair?server=https%3A%2F%2Fa.example&token=tsp_abcdefgh12345")
        }
    }

    @Test
    fun `plain http servers are rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            PairingUri.parse("tinysonic://pair?server=http%3A%2F%2Fa.example&token=tsp_abcdefgh12345")
        }
    }

    @Test
    fun `missing server or token is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            PairingUri.parse("tinysonic://pair?token=tsp_abcdefgh12345")
        }
        assertThrows(IllegalArgumentException::class.java) {
            PairingUri.parse("tinysonic://pair?server=https%3A%2F%2Fa.example")
        }
    }

    @Test
    fun `token with unsafe characters is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            PairingUri.parse("tinysonic://pair?server=https%3A%2F%2Fa.example&token=abc%26apiKey%3Dx")
        }
    }

    @Test
    fun `garbage input is rejected cleanly`() {
        assertThrows(IllegalArgumentException::class.java) { PairingUri.parse("not a uri at all") }
    }

    @Test
    fun `the certificate pin in the code is kept, with its plus signs`() {
        val code =
            "tinysonic://pair?server=https%3A%2F%2F192.168.1.10%3A4533%2F&token=tsp_abcdefgh12345" +
                "&pin=sha256%2FAIi%2BHIhAaC%2FH6hu%2BvF2gpQ%2F97rDOTGrt52bobf0fjdM%3D"

        assertEquals("sha256/AIi+HIhAaC/H6hu+vF2gpQ/97rDOTGrt52bobf0fjdM=", PairingUri.parse(code).pin)
    }

    @Test
    fun `a code without a pin has none`() {
        val code = "tinysonic://pair?server=https%3A%2F%2Fcloud.example.com%2F&token=tsp_abcdefgh12345"

        assertEquals(null, PairingUri.parse(code).pin)
    }

    @Test
    fun `a malformed pin is refused`() {
        assertThrows(IllegalArgumentException::class.java) {
            PairingUri.parse(
                "tinysonic://pair?server=https%3A%2F%2F192.168.1.10%2F&token=tsp_abcdefgh12345&pin=md5%2Fxyz",
            )
        }
    }
}
