package org.dancesonic.player.account

import org.dancesonic.player.subsonic.PublicJwk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class VerificationCodeTest {
    // Expected values come from an independent Python computation of the same definition, not from this code.
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
    fun `code matches the fixed vector`() {
        assertEquals("812 567", VerificationCode.of("tok_Example-123", jwk))
    }

    @Test
    fun `another device key gives another code for the same token`() {
        val other = jwk.copy(x = jwk.y, y = jwk.x)
        assertNotEquals(VerificationCode.of("tok_Example-123", jwk), VerificationCode.of("tok_Example-123", other))
    }

    @Test
    fun `another token gives another code for the same key`() {
        assertNotEquals(VerificationCode.of("tok_Example-123", jwk), VerificationCode.of("tok_Example-124", jwk))
    }

    @Test
    fun `code is six digits in two groups of three`() {
        assertEquals(true, Regex("""\d{3} \d{3}""").matches(VerificationCode.of("tok_Example-123", jwk)))
    }
}
