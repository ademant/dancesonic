package org.dancesonic.player.studio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.util.UUID

/** The relay bytes of Studio's `docs/remote-protocol.md` (#104). Expected values are computed from its formulas. */
class RelayWireTest {
    private val uuid = UUID.fromString("7c3b0001-5b9e-4c2c-9c6a-2d1f5e0a4b10")
    private val noise = ByteArray(32) { 9 }
    private val spki = ByteArray(91) { (it * 3).toByte() }

    private fun recordText(version: Byte = 1): String {
        val uuidBytes = ByteBuffer.allocate(16).putLong(uuid.mostSignificantBits).putLong(uuid.leastSignificantBits)
        return StudioWire.base64Url(byteArrayOf(version) + noise + uuidBytes.array() + spki)
    }

    @Test
    fun `the comparison code is SHA-256 of the label and the binding, mod a million, as six digits`() {
        // python: sha256(b"dancesonic-compare-v1\0" + bytes([7]*32)), first 4 bytes big-endian % 1_000_000
        assertEquals("689063", RelayWire.comparisonCode(ByteArray(32) { 7 }))
        assertEquals(6, RelayWire.comparisonCode(ByteArray(32)).length)
        assertTrue(RelayWire.comparisonCode(ByteArray(32) { 7 }) != RelayWire.comparisonCode(ByteArray(32) { 8 }))
    }

    @Test
    fun `Studio's relay id comes from its fingerprint`() {
        assertEquals("studio-JDI2gLRUyY-koOk2", RelayWire.studioDeviceId(ByteArray(32) { 1 }))
        assertTrue(RelayWire.validDeviceId(RelayWire.studioDeviceId(ByteArray(32) { 1 })))
    }

    @Test
    fun `a new phone id is valid on folksonic and different each time`() {
        val a = RelayWire.newPhoneId()
        assertTrue(a.startsWith("phone-"))
        assertTrue(RelayWire.validDeviceId(a))
        assertTrue(a != RelayWire.newPhoneId())
        assertFalse(RelayWire.validDeviceId(""))
        assertFalse(RelayWire.validDeviceId("a/b"))
        assertFalse(RelayWire.validDeviceId("x".repeat(65)))
    }

    @Test
    fun `a Studio record is parsed and gives the fingerprint of its keys`() {
        val record = StudioRecord.parse(recordText())!!

        assertArrayEquals(noise, record.noisePublic)
        assertEquals(uuid, record.bleService)
        assertArrayEquals(spki, record.tlsSpki)
        assertArrayEquals(StudioWire.fingerprint(spki, noise), record.fingerprint)
    }

    @Test
    fun `a record of another version or too short is refused`() {
        assertNull(StudioRecord.parse(recordText(version = 2)))
        assertNull(StudioRecord.parse(StudioWire.base64Url(byteArrayOf(1) + noise)))
        assertNull(StudioRecord.parse("not base64 !"))
        assertNull(StudioRecord.parse(""))
    }

    @Test
    fun `a frame carries the device id and the payload`() {
        val frame = RelayWire.encodeFrame("studio-abc", byteArrayOf(2, 5, 6))

        assertEquals(10, frame[0].toInt())
        val (from, payload) = RelayWire.decodeFrame(frame)
        assertEquals("studio-abc", from)
        assertArrayEquals(byteArrayOf(2, 5, 6), payload)
    }

    @Test
    fun `frames over the relay limit, with a bad id or without payload are refused`() {
        assertThrows(RelayFormatException::class.java) {
            RelayWire.encodeFrame("studio-abc", ByteArray(RelayWire.MAX_RELAY_MESSAGE))
        }
        assertThrows(RelayFormatException::class.java) { RelayWire.encodeFrame("bad id", byteArrayOf(1)) }
        assertThrows(RelayFormatException::class.java) { RelayWire.decodeFrame(byteArrayOf(3, 'a'.code.toByte())) }
        assertThrows(RelayFormatException::class.java) { RelayWire.decodeFrame(ByteArray(0)) }
    }

    @Test
    fun `a large message is split into chunks under the limit and joined back`() {
        val message = ByteArray(40 * 1024) { (it % 251).toByte() }
        val chunks = RelayWire.chunks(message)

        assertEquals(3, chunks.size)
        assertTrue(chunks.all { it.size <= RelayWire.CHUNK + 1 })
        assertEquals(listOf(0, 0, 1), chunks.map { it[0].toInt() })
        val joiner = RelayJoiner(RelayWire.MAX_INCOMING)
        assertNull(joiner.push(chunks[0]))
        assertNull(joiner.push(chunks[1]))
        assertArrayEquals(message, joiner.push(chunks[2]))
    }

    @Test
    fun `an empty message is one final chunk`() {
        val chunks = RelayWire.chunks(ByteArray(0))

        assertEquals(1, chunks.size)
        assertArrayEquals(ByteArray(0), RelayJoiner(10).push(chunks[0]))
    }

    @Test
    fun `the joiner refuses unknown flags and messages over its limit`() {
        assertThrows(RelayFormatException::class.java) { RelayJoiner(10).push(byteArrayOf(2, 1)) }
        assertThrows(RelayFormatException::class.java) { RelayJoiner(2).push(byteArrayOf(1, 1, 2, 3)) }
        assertThrows(RelayFormatException::class.java) { RelayJoiner(2).push(ByteArray(0)) }
    }

    @Test
    fun `the payload kind is checked`() {
        val payload = RelayWire.payload(RelayKind.TRANSPORT, byteArrayOf(4))

        assertArrayEquals(byteArrayOf(4), RelayWire.expect(payload, RelayKind.TRANSPORT))
        assertThrows(RelayFormatException::class.java) { RelayWire.expect(payload, RelayKind.HANDSHAKE) }
    }

    @Test
    fun `the code pairing transcript has its own label`() {
        val fp = ByteArray(32) { 1 }
        val transcript = RelayWire.pairCodeTranscript(fp, ByteArray(65) { 2 }, ByteArray(32) { 3 }, ByteArray(32) { 4 })

        assertTrue(transcript.copyOf(24).contentEquals("dancesonic-pair-code-v1\u0000".toByteArray()))
        assertEquals(24 + 32 + 65 + 32 + 32, transcript.size)
        assertArrayEquals(
            "dancesonic-relay-v1\u0000".toByteArray() + fp,
            RelayWire.prologue(fp),
        )
    }
}
