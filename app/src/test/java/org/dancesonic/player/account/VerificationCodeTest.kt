package org.dancesonic.player.account

import org.dancesonic.player.subsonic.PublicJwk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VerificationCodeTest {
    // The test vector from folksonic #103's final definition: the server computes the same code from these inputs.
    private val folksonicThumbprint = "NzbLsXh8uDCcd-6MNwXF4W_7noWXFZAfHkxZsRGC9Xs"

    // Coordinates with an independently computed thumbprint, so the RFC 7638 canonicalisation is checked too.
    private val jwk =
        PublicJwk(
            kty = "EC",
            crv = "P-256",
            x = "AQIDBAUGBwgJCgsMDQ4PEBESExQVFhcYGRobHB0eHyA",
            y = "ISIjJCUmJygpKissLS4vMDEyMzQ1Njc4OTo7PD0-P0A",
        )

    @Test
    fun `thumbprint matches the RFC 7638 value computed independently`() {
        assertEquals("t1ZI8tOt77KZ9YepYcUiqtqXcpIYInMJhkFb6casAFo", jwk.thumbprint())
    }

    @Test
    fun `code matches folksonic's test vector`() {
        assertEquals("446 284", VerificationCode.of("tsp_example-pairing-token", folksonicThumbprint))
    }

    @Test
    fun `code from a key is the code from its thumbprint`() {
        assertEquals(
            VerificationCode.of("tsp_example-pairing-token", jwk.thumbprint()),
            VerificationCode.of("tsp_example-pairing-token", jwk),
        )
    }

    @Test
    fun `another device key gives another code for the same token`() {
        val other = jwk.copy(x = jwk.y, y = jwk.x)
        assertNotEquals(
            VerificationCode.of("tsp_example-pairing-token", jwk),
            VerificationCode.of("tsp_example-pairing-token", other),
        )
    }

    @Test
    fun `another token gives another code for the same key`() {
        assertNotEquals(
            VerificationCode.of("tsp_example-pairing-token", jwk),
            VerificationCode.of("tsp_other-token", jwk),
        )
    }

    @Test
    fun `code is six digits in two groups of three`() {
        assertTrue(Regex("""\d{3} \d{3}""").matches(VerificationCode.of("tsp_example-pairing-token", jwk)))
    }
}
