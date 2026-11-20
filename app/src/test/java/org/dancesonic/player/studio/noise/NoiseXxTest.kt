package org.dancesonic.player.studio.noise

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.security.SecureRandom

class NoiseXxTest {
    private val prologue = "dancesonic-ble-v1\u0000".toByteArray() + ByteArray(16) { 7 }

    /** Runs the three handshake messages between an initiator and a responder with [prologueB] on the responder. */
    private fun handshake(
        prologueB: ByteArray = prologue,
        payloadA: ByteArray = byteArrayOf(1),
        payloadB: ByteArray = byteArrayOf(2),
    ): Triple<NoiseXxInitiator, NoiseXxResponder, Pair<NoiseKeyPair, NoiseKeyPair>> {
        val phone = NoiseKeyPair.generate()
        val studio = NoiseKeyPair.generate()
        val initiator = NoiseXxInitiator(phone, prologue)
        val responder = NoiseXxResponder(studio, prologueB)
        assertArrayEquals(payloadA, responder.readFirst(initiator.writeFirst(payloadA)))
        assertArrayEquals(payloadB, initiator.readSecond(responder.writeSecond(payloadB)))
        responder.readThird(initiator.writeThird())
        return Triple(initiator, responder, phone to studio)
    }

    @Test
    fun `the public key of the RFC 7748 Alice scalar is the RFC's public key`() {
        val alice = NoiseKeyPair.fromPrivate(hex("77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a"))

        assertArrayEquals(hex("8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a"), alice.publicKey)
    }

    @Test
    fun `both sides finish with the same handshake hash and know each other's static key`() {
        val (initiator, responder, keys) = handshake()

        assertArrayEquals(initiator.handshakeHash, responder.handshakeHash)
        assertArrayEquals(keys.first.publicKey, responder.remoteStatic)
        assertArrayEquals(keys.second.publicKey, initiator.remoteStatic)
    }

    @Test
    fun `transport messages arrive intact in both directions`() {
        val (initiator, responder, _) = handshake()
        val phoneToStudio = initiator.transport.encrypt("command".toByteArray())
        val studioToPhone = responder.transport.encrypt("state".toByteArray())

        assertEquals("command", String(responder.transport.decrypt(phoneToStudio)))
        assertEquals("state", String(initiator.transport.decrypt(studioToPhone)))
    }

    @Test
    fun `the same message sent twice is encrypted differently`() {
        val (initiator, _, _) = handshake()
        val first = initiator.transport.encrypt(byteArrayOf(9, 9))
        val second = initiator.transport.encrypt(byteArrayOf(9, 9))

        assertFalse(first.contentEquals(second))
    }

    @Test
    fun `a different prologue fails the handshake, so the service UUID is bound to the session`() {
        val phone = NoiseKeyPair.generate()
        val studio = NoiseKeyPair.generate()
        val initiator = NoiseXxInitiator(phone, prologue)
        val responder = NoiseXxResponder(studio, prologue + byteArrayOf(0))

        responder.readFirst(initiator.writeFirst())
        val second = responder.writeSecond()
        // The prologue is mixed into the hash before any key, so Studio's static key cannot be opened.
        assertThrows(NoiseException::class.java) { initiator.readSecond(second) }
    }

    @Test
    fun `a tampered transport message is refused`() {
        val (initiator, responder, _) = handshake()
        val sealed = initiator.transport.encrypt("state".toByteArray())
        sealed[0] = (sealed[0].toInt() xor 1).toByte()

        assertThrows(NoiseException::class.java) { responder.transport.decrypt(sealed) }
    }

    @Test
    fun `a truncated second message is refused`() {
        val phone = NoiseKeyPair.generate()
        val initiator = NoiseXxInitiator(phone, prologue)
        initiator.writeFirst()

        assertThrows(NoiseException::class.java) { initiator.readSecond(ByteArray(10)) }
    }

    @Test
    fun `two handshakes never share a handshake hash`() {
        val first = handshake().first.handshakeHash
        val second = handshake().first.handshakeHash

        assertNotEquals(first.toList(), second.toList())
    }

    @Test
    fun `random key pairs are different and match their private key`() {
        val random = SecureRandom()
        val a = NoiseKeyPair.generate(random)
        val b = NoiseKeyPair.generate(random)

        assertNotEquals(a.publicKey.toList(), b.publicKey.toList())
        assertArrayEquals(a.publicKey, NoiseKeyPair.fromPrivate(a.privateKey).publicKey)
    }

    private fun hex(text: String): ByteArray =
        ByteArray(text.length / 2) {
            text.substring(it * 2, it * 2 + 2).toInt(16).toByte()
        }
}
