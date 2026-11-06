package org.dancesonic.player.dance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class DanceBrainzPairingUriTest {
    @Test
    fun `valid code yields server and code`() {
        val request =
            DanceBrainzPairingUri.parse(
                "dancebrainz://pair?server=https%3A%2F%2Fdancebrainz.example%2F&token=dbp_abcdefghijklmnop1234",
            )
        assertEquals("https://dancebrainz.example/", request.server.toString())
        assertEquals("dbp_abcdefghijklmnop1234", request.token)
    }

    @Test
    fun `the redacted string never shows the code`() {
        val request =
            DanceBrainzPairingUri.parse(
                "dancebrainz://pair?server=https%3A%2F%2Fdancebrainz.example%2F&token=dbp_abcdefghijklmnop1234",
            )
        assertEquals(false, request.toString().contains("dbp_"))
    }

    @Test
    fun `other schemes are rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            DanceBrainzPairingUri.parse(
                "tinysonic://pair?server=https%3A%2F%2Fa.example&token=dbp_abcdefghijklmnop1234",
            )
        }
    }

    @Test
    fun `a plain http server is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            DanceBrainzPairingUri.parse(
                "dancebrainz://pair?server=http%3A%2F%2Fa.example&token=dbp_abcdefghijklmnop1234",
            )
        }
    }

    @Test
    fun `a code without the dbp prefix is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            DanceBrainzPairingUri.parse(
                "dancebrainz://pair?server=https%3A%2F%2Fa.example&token=dbz_abcdefghijklmnop1234",
            )
        }
    }
}
