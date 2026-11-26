package org.dancesonic.player.studio

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.dancesonic.player.studio.noise.NoiseKeyPair
import org.dancesonic.player.studio.noise.NoiseTransport
import org.dancesonic.player.studio.noise.NoiseXxInitiator
import org.dancesonic.player.studio.noise.NoiseXxResponder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * Stands in for Studio at the other end of the relay: answers the Noise handshake for the prologue of [fingerprint],
 * sends `identity`, then records what the phone sends. Payloads to the phone can be sent, replayed or changed.
 */
internal class FakeRelayStudio(
    studioKey: NoiseKeyPair,
    private val tlsSpki: ByteArray,
    fingerprint: ByteArray,
) : RelayPipe {
    override val incoming = Channel<ByteArray>(Channel.UNLIMITED)
    val responder = NoiseXxResponder(studioKey, RelayWire.prologue(fingerprint))
    private var step = 0
    private var noise: NoiseTransport? = null
    private val joiner = RelayJoiner(RelayWire.MAX_INCOMING)
    val received = mutableListOf<String>()
    val transportPayloads = mutableListOf<Int>()
    var closed = false
        private set

    override suspend fun send(payload: ByteArray) {
        when (step++) {
            0 -> {
                responder.readFirst(RelayWire.expect(payload, RelayKind.START))
                incoming.send(RelayWire.payload(RelayKind.HANDSHAKE, responder.writeSecond()))
            }
            1 -> {
                responder.readThird(RelayWire.expect(payload, RelayKind.HANDSHAKE))
                noise = responder.transport
                toPhone("""{"type":"identity","tls_spki":"${StudioWire.base64Url(tlsSpki)}"}""")
            }
            else -> {
                transportPayloads += payload.size
                val plain = noise!!.decrypt(RelayWire.expect(payload, RelayKind.TRANSPORT))
                joiner.push(plain)?.let { received += it.toString(Charsets.UTF_8) }
            }
        }
    }

    /** The sealed payloads of one message, without sending them. */
    fun seal(text: String): List<ByteArray> =
        RelayWire.chunks(text.toByteArray()).map { RelayWire.payload(RelayKind.TRANSPORT, noise!!.encrypt(it)) }

    suspend fun toPhone(text: String) = seal(text).forEach { incoming.send(it) }

    override fun close() {
        closed = true
        incoming.close()
    }
}

class RelayStudioLinkTest {
    private val tlsSpki = ByteArray(91) { (it * 3).toByte() }
    private val studioKey = NoiseKeyPair.generate()
    private val phoneKey = NoiseKeyPair.generate()
    private val fingerprint = StudioWire.fingerprint(tlsSpki, studioKey.publicKey)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    private fun isPaired(
        spki: ByteArray,
        noise: ByteArray,
    ) = StudioWire.fingerprint(spki, noise).contentEquals(fingerprint)

    private suspend fun open(studio: FakeRelayStudio): StudioLink =
        RelayStudioOpener.open(studio, fingerprint, phoneKey, scope, ::isPaired, stepTimeoutMillis = 2_000)

    private suspend fun StudioLink.next(): String? = withTimeout(2_000) { incoming.receiveCatching().getOrNull() }

    @Test
    fun `the link opens after the handshake and identity, and carries text both ways`() =
        runBlocking<Unit> {
            val studio = FakeRelayStudio(studioKey, tlsSpki, fingerprint)
            val link = open(studio)

            link.send("""{"type":"hello"}""")
            studio.toPhone("""{"type":"welcome","transport":"relay"}""")

            assertEquals(listOf("""{"type":"hello"}"""), studio.received)
            assertEquals("""{"type":"welcome","transport":"relay"}""", link.next())
            assertArrayEquals(studio.responder.handshakeHash, link.binding)
            assertArrayEquals(phoneKey.publicKey, studio.responder.remoteStatic)
            link.close()
        }

    @Test
    fun `a Studio whose keys do not match the record is refused before anything is sent`() =
        runBlocking<Unit> {
            // The record claims [fingerprint]; the Studio that answers holds other keys under the same prologue.
            val other = NoiseKeyPair.generate()
            val studio = FakeRelayStudio(other, tlsSpki, fingerprint)

            val error = assertThrows(RelayStudioException::class.java) { runBlocking { open(studio) } }

            assertTrue(error.notThisStudio)
            assertTrue(studio.received.isEmpty())
            assertTrue(studio.closed)
        }

    @Test
    fun `a Studio that was not given this fingerprint fails the handshake`() =
        runBlocking<Unit> {
            val studio = FakeRelayStudio(studioKey, tlsSpki, ByteArray(32) { 4 })

            val error = assertThrows(RelayStudioException::class.java) { runBlocking { open(studio) } }

            assertFalse(error.notThisStudio)
            assertTrue(studio.closed)
        }

    @Test
    fun `a replayed frame ends the session, so nothing arrives twice`() =
        runBlocking<Unit> {
            val studio = FakeRelayStudio(studioKey, tlsSpki, fingerprint)
            val link = open(studio)
            val sealed = studio.seal("""{"type":"result","id":1,"ok":true}""")

            sealed.forEach { studio.incoming.send(it) }
            sealed.forEach { studio.incoming.send(it) }

            assertEquals("""{"type":"result","id":1,"ok":true}""", link.next())
            assertEquals(null, link.next())
        }

    @Test
    fun `a reordered frame ends the session`() =
        runBlocking<Unit> {
            val studio = FakeRelayStudio(studioKey, tlsSpki, fingerprint)
            val link = open(studio)
            val first = studio.seal("""{"n":1}""").single()
            val second = studio.seal("""{"n":2}""").single()

            studio.incoming.send(second)
            // The link may already have ended the session on the out-of-order frame and closed the channel, so this
            // send may fail; it was a race in this test that flaked the build.
            studio.incoming.trySend(first)

            assertEquals(null, link.next())
        }

    @Test
    fun `a changed frame ends the session`() =
        runBlocking<Unit> {
            val studio = FakeRelayStudio(studioKey, tlsSpki, fingerprint)
            val link = open(studio)
            val sealed = studio.seal("""{"type":"state"}""").single()
            sealed[sealed.size - 1] = (sealed[sealed.size - 1].toInt() xor 1).toByte()

            studio.incoming.send(sealed)

            assertEquals(null, link.next())
        }

    @Test
    fun `a large answer is rebuilt from its chunks`() =
        runBlocking<Unit> {
            val studio = FakeRelayStudio(studioKey, tlsSpki, fingerprint)
            val link = open(studio)
            val big = """{"type":"result","data":"${"x".repeat(200 * 1024)}"}"""

            studio.toPhone(big)

            assertEquals(big, link.next())
        }

    @Test
    fun `the phone splits a long message into frames under the relay limit, and refuses one over 64 KiB`() =
        runBlocking<Unit> {
            val studio = FakeRelayStudio(studioKey, tlsSpki, fingerprint)
            val link = open(studio)
            val long = "y".repeat(40 * 1024)

            link.send(long)

            assertEquals(listOf(long), studio.received)
            assertEquals(3, studio.transportPayloads.size)
            assertTrue(studio.transportPayloads.all { it < RelayWire.MAX_RELAY_MESSAGE - 64 })
            assertThrows(RelayFormatException::class.java) {
                runBlocking { link.send("z".repeat(RelayWire.MAX_OUTGOING + 1)) }
            }
        }

    @Test
    fun `Studio leaving the relay ends the session`() =
        runBlocking<Unit> {
            val studio = FakeRelayStudio(studioKey, tlsSpki, fingerprint)
            val link = open(studio)

            studio.incoming.close(IOException("Studio is not connected to the relay"))

            assertEquals(null, link.next())
        }

    @Test
    fun `a server in the middle gets a different comparison code on each side`() =
        runBlocking<Unit> {
            // The server swaps Studio's record for its own and answers the phone itself...
            val middleKey = NoiseKeyPair.generate()
            val middleSpki = ByteArray(91) { 7 }
            val fakeFingerprint = StudioWire.fingerprint(middleSpki, middleKey.publicKey)
            val middle = FakeRelayStudio(middleKey, middleSpki, fakeFingerprint)
            val phoneLink =
                RelayStudioOpener.open(middle, fakeFingerprint, phoneKey, scope, { _, _ -> true }, 2_000)

            // ...and runs its own handshake with the real Studio.
            val towardsStudio = NoiseXxInitiator(NoiseKeyPair.generate(), RelayWire.prologue(fingerprint))
            val studio = NoiseXxResponder(studioKey, RelayWire.prologue(fingerprint))
            studio.readFirst(towardsStudio.writeFirst())
            towardsStudio.readSecond(studio.writeSecond())
            studio.readThird(towardsStudio.writeThird())

            assertNotEquals(
                RelayWire.comparisonCode(phoneLink.binding),
                RelayWire.comparisonCode(studio.handshakeHash),
            )
        }
}
