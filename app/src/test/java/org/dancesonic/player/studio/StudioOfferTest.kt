package org.dancesonic.player.studio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StudioOfferTest {
    private val now = 1_000L
    private val fp = StudioWire.base64Url(ByteArray(32) { 1 })
    private val secret = StudioWire.base64Url(ByteArray(16) { 2 })
    private val ble = "7c3b0001-5b9e-4c2c-9c6a-2d1f5e0a4b10"

    private fun code(
        version: String = "1",
        fingerprint: String = fp,
        exp: Long = now + 300,
        name: String = "Living+room",
    ) = "dancesonic-studio:pair?v=$version&fp=$fingerprint&s=$secret&exp=$exp&ble=$ble&name=$name"

    @Test
    fun `a valid code gives Studio's fingerprint, the secret, the expiry and the name`() {
        val result = StudioOffer.parse(code(), now) as StudioOffer.ParseResult.Ok

        assertEquals(32, result.offer.fingerprint.size)
        assertEquals(16, result.offer.secret.size)
        assertEquals(now + 300, result.offer.expiresAt)
        assertEquals(ble, result.offer.bleService)
        assertEquals("Living room", result.offer.name)
    }

    @Test
    fun `a code of another kind is not a Studio code`() {
        assertEquals(
            StudioOffer.ParseResult.Refused(StudioOffer.Problem.NOT_A_STUDIO_CODE),
            StudioOffer.parse("https://x", now),
        )
    }

    @Test
    fun `another protocol version is refused`() {
        assertEquals(
            StudioOffer.ParseResult.Refused(StudioOffer.Problem.UNSUPPORTED_VERSION),
            StudioOffer.parse(code(version = "2"), now),
        )
    }

    @Test
    fun `a code without its fingerprint is refused as missing`() {
        val text = "dancesonic-studio:pair?v=1&s=$secret&exp=${now + 300}&ble=$ble&name=x"

        assertEquals(StudioOffer.ParseResult.Refused(StudioOffer.Problem.MISSING_FIELD), StudioOffer.parse(text, now))
    }

    @Test
    fun `a fingerprint of the wrong length is refused`() {
        val short = StudioWire.base64Url(ByteArray(31) { 1 })

        assertEquals(
            StudioOffer.ParseResult.Refused(StudioOffer.Problem.BAD_FIELD),
            StudioOffer.parse(code(fingerprint = short), now),
        )
    }

    @Test
    fun `a BLE service that is not a UUID is refused`() {
        val text = code().replace(ble, "not-a-uuid")

        assertEquals(StudioOffer.ParseResult.Refused(StudioOffer.Problem.BAD_FIELD), StudioOffer.parse(text, now))
    }

    @Test
    fun `an expired code is refused`() {
        assertEquals(
            StudioOffer.ParseResult.Refused(StudioOffer.Problem.EXPIRED),
            StudioOffer.parse(code(exp = now), now),
        )
    }

    @Test
    fun `a repeated field makes the whole code bad`() {
        assertEquals(
            StudioOffer.ParseResult.Refused(StudioOffer.Problem.BAD_FIELD),
            StudioOffer.parse(code() + "&v=1", now),
        )
    }

    @Test
    fun `the name is limited to 64 characters`() {
        val long = "x".repeat(65)

        assertEquals(
            StudioOffer.ParseResult.Refused(StudioOffer.Problem.BAD_FIELD),
            StudioOffer.parse(code(name = long), now),
        )
    }

    @Test
    fun `an offer is open until its expiry`() {
        val offer = (StudioOffer.parse(code(), now) as StudioOffer.ParseResult.Ok).offer

        assertTrue(offer.isOpen(now + 299))
        assertFalse(offer.isOpen(now + 300))
    }
}
