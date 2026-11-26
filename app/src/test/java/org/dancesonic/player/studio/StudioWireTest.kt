package org.dancesonic.player.studio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The expected values come from an independent Python implementation of `docs/remote-protocol.md`, so these tests
 * check the Kotlin bytes against the specification rather than against themselves.
 */
class StudioWireTest {
    private val spki = ByteArray(10) { it.toByte() }
    private val noise = ByteArray(32) { 0x11 }
    private val binding = ByteArray(32) { 0x33 }

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    @Test
    fun `the fingerprint binds the TLS key and the Noise key`() {
        val fp = StudioWire.fingerprint(spki, noise)

        assertEquals("472803d032a3399d92bfd25bb24be9bd528b32655bbbb3bbdc56861c6fd7ce05", hex(fp))
        assertEquals("RygD0DKjOZ2Sv9JbskvpvVKLMmVbu7O73FaGHG_XzgU", StudioWire.base64Url(fp))
    }

    @Test
    fun `the mDNS tag is the labelled hash of the fingerprint, eight bytes`() {
        val fp = StudioWire.fingerprint(spki, noise)

        assertEquals("FG20or5WKII", StudioWire.mdnsTag(fp))
    }

    @Test
    fun `the pairing transcript is the label, fingerprint, device key, Noise key and binding in order`() {
        val fp = StudioWire.fingerprint(spki, noise)
        val devicePoint = ByteArray(65) { if (it == 0) 0x04 else 0x22 }

        val transcript = StudioWire.pairTranscript(fp, devicePoint, noise, binding)

        assertEquals(180, transcript.size)
        assertEquals(
            "8ac279d5b6489cbd63165b72995c90fa6fc235351778cbbc12b8993fd2e32c57",
            sha256Hex(transcript),
        )
    }

    @Test
    fun `hello covers the label, the device id, the counter as eight big-endian bytes and the binding`() {
        assertEquals(
            "64616e6365736f6e69632d68656c6c6f2d7631006465762d31000000000000000" +
                "73333333333333333333333333333333333333333333333333333333333333333",
            hex(StudioWire.helloBytes("dev-1", 7, binding)),
        )
    }

    @Test
    fun `a command covers the exact UTF-8 body text`() {
        assertEquals(
            "64616e6365736f6e69632d636d642d7631006465762d3100000000000001027b22636d64223a22706c6179227d",
            hex(StudioWire.commandBytes("dev-1", 258, """{"cmd":"play"}""")),
        )
    }

    @Test
    fun `counters are eight bytes, the largest one exact`() {
        assertEquals("001fffffffffffff", hex(StudioWire.u64((1L shl 53) - 1)))
    }

    @Test
    fun `base64url has no padding and uses the URL alphabet`() {
        assertEquals("-_8", StudioWire.base64Url(byteArrayOf(0xfb.toByte(), 0xff.toByte())))
    }

    @Test
    fun `decoding refuses text that is too long`() {
        assertNull(StudioWire.decodeBase64Url(StudioWire.base64Url(ByteArray(17)), 16))
    }

    private fun sha256Hex(bytes: ByteArray): String =
        hex(
            java.security.MessageDigest
                .getInstance("SHA-256")
                .digest(bytes),
        )
}
