package org.dancesonic.player.account

import org.dancesonic.player.subsonic.PublicJwk
import java.math.BigInteger
import java.security.MessageDigest

/**
 * The short code that the phone and the folksonic account page both show during pairing (folksonic #103), so the owner
 * can check that the phone they see is the one that scanned the code. It follows folksonic's final definition, which
 * replaced the first formula in the issue, because the server keeps only the token's hash:
 *
 *   token_hash = lower-case hex(SHA-256(token))
 *   digest     = SHA-256(token_hash + ":" + thumbprint)     (thumbprint: RFC 7638 of the device key)
 *   code       = big-endian uint32(digest[0..4]) mod 1,000,000, zero-padded, shown as `123 456`
 *
 * Nothing here is sent to the server.
 */
object VerificationCode {
    private const val MODULUS = 1_000_000L
    private const val DIGITS = 6
    private const val PREFIX_BYTES = 4

    /** The code for [token] and the device key [jwk]. Both sides compute it; neither sends it. */
    fun of(
        token: String,
        jwk: PublicJwk,
    ): String = of(token, jwk.thumbprint())

    /** The code for [token] and an RFC 7638 [thumbprint] of the device key. */
    fun of(
        token: String,
        thumbprint: String,
    ): String {
        val tokenHash = sha256(token.toByteArray(Charsets.UTF_8)).toHex()
        val digest = sha256("$tokenHash:$thumbprint".toByteArray(Charsets.UTF_8))
        val value = BigInteger(1, digest.copyOfRange(0, PREFIX_BYTES)).toLong() % MODULUS
        val digits = value.toString().padStart(DIGITS, '0')
        return digits.substring(0, DIGITS / 2) + " " + digits.substring(DIGITS / 2)
    }

    private fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
