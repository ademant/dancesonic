package org.dancesonic.player.account

import org.dancesonic.player.subsonic.PublicJwk
import java.math.BigInteger
import java.security.MessageDigest

/**
 * The short code that the phone and the folksonic account page both show during pairing (folksonic #103), so the owner
 * can check that the phone they see is the one that scanned the code.
 *
 * The code is the first six decimal digits of `SHA-256(token ‖ thumbprint)`, where `‖` is concatenation of the UTF-8
 * bytes and the thumbprint is the RFC 7638 thumbprint of the device key. The digest is read as an unsigned integer,
 * written in decimal, and its first six digits are shown as `123 456`. The server computes it the same way. It is
 * never sent anywhere.
 */
object VerificationCode {
    private const val DIGITS = 6

    /** The code for [token] and the device key [jwk]. Both sides compute it; neither sends it. */
    fun of(
        token: String,
        jwk: PublicJwk,
    ): String {
        val input = token.toByteArray(Charsets.UTF_8) + jwk.thumbprint().toByteArray(Charsets.UTF_8)
        val digest = MessageDigest.getInstance("SHA-256").digest(input)
        val digits = BigInteger(1, digest).toString().take(DIGITS)
        return digits.substring(0, DIGITS / 2) + " " + digits.substring(DIGITS / 2)
    }
}
