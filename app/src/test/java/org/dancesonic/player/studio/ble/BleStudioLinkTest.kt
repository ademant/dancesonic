package org.dancesonic.player.studio.ble

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.dancesonic.player.studio.StudioWire
import org.dancesonic.player.studio.noise.NoiseKeyPair
import org.dancesonic.player.studio.noise.NoiseTransport
import org.dancesonic.player.studio.noise.NoiseXxResponder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.UUID

/** Stands in for Studio on the Bluetooth link: answers the handshake, sends `identity`, then echoes commands. */
private class FakeStudio(
    private val serviceUuid: UUID,
    private val studioKey: NoiseKeyPair,
    private val tlsSpki: ByteArray,
    private val silent: Boolean = false,
) : BleTransport {
    override val mtu = 247
    override val notifications = Channel<ByteArray>(Channel.UNLIMITED)
    private val inbound = BleReassembler()
    private val responder = NoiseXxResponder(studioKey, blePrologue(serviceUuid))
    private var step = 0
    private var noise: NoiseTransport? = null
    var receivedText: String? = null
        private set

    override suspend fun write(frame: ByteArray) {
        if (silent) return
        val message = inbound.push(frame) ?: return
        when (step++) {
            0 -> {
                responder.readFirst(message)
                emit(responder.writeSecond())
            }
            1 -> {
                responder.readThird(message)
                val identity = """{"type":"identity","tls_spki":"${StudioWire.base64Url(tlsSpki)}"}"""
                noise = responder.transport
                emit(noise!!.encrypt(identity.toByteArray()))
            }
            else -> {
                val text = noise!!.decrypt(message).toString(Charsets.UTF_8)
                receivedText = text
                val welcome = """{"type":"welcome","protocol":1,"name":"Studio","transport":"ble","features":[]}"""
                emit(noise!!.encrypt(welcome.toByteArray()))
            }
        }
    }

    private suspend fun emit(message: ByteArray) {
        for (frame in BleFraming.frames(message, mtu)) notifications.send(frame)
    }

    override fun close() {
        notifications.close()
    }
}

class BleStudioLinkTest {
    private val serviceUuid = UUID.fromString("7c3b0001-5b9e-4c2c-9c6a-2d1f5e0a4b10")
    private val tlsSpki = ByteArray(91) { (it * 3).toByte() }
    private val studioKey = NoiseKeyPair.generate()
    private val phoneKey = NoiseKeyPair.generate()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @Test
    fun `the link opens after the handshake and carries text both ways`() =
        runBlocking {
            val studio = FakeStudio(serviceUuid, studioKey, tlsSpki)
            var seenSpki: ByteArray? = null
            var seenNoise: ByteArray? = null

            val link =
                BleStudioOpener.open(studio, serviceUuid, phoneKey, scope, isStudio = { spki, noise ->
                    seenSpki = spki
                    seenNoise = noise
                    true
                })
            link.send("""{"type":"hello"}""")
            val welcome = withTimeout(2_000) { link.incoming.receive() }

            assertArrayEquals(tlsSpki, seenSpki)
            assertArrayEquals(studioKey.publicKey, seenNoise)
            assertEquals("""{"type":"hello"}""", studio.receivedText)
            assertEquals(true, welcome.contains("\"welcome\""))
            assertEquals(32, link.binding.size)
            link.close()
        }

    @Test
    fun `a Studio that is not the paired one is refused before anything is sent`() =
        runBlocking {
            val studio = FakeStudio(serviceUuid, studioKey, tlsSpki)

            val error =
                assertThrows(BleStudioException::class.java) {
                    runBlocking {
                        BleStudioOpener.open(studio, serviceUuid, phoneKey, scope, isStudio = {
                                _,
                                _,
                            ->
                            false
                        })
                    }
                }

            assertEquals("the Studio on Bluetooth is not the one paired", error.message)
            assertEquals(null, studio.receivedText)
        }

    @Test
    fun `a Studio that never answers times out`() {
        val studio = FakeStudio(serviceUuid, studioKey, tlsSpki, silent = true)

        assertThrows(BleStudioException::class.java) {
            runBlocking {
                BleStudioOpener.open(
                    studio,
                    serviceUuid,
                    phoneKey,
                    scope,
                    isStudio = { _, _ -> true },
                    stepTimeoutMillis = 200,
                )
            }
        }
    }

    @Test
    fun `the prologue carries the service UUID, so another UUID gives another session`() {
        val other = UUID.fromString("00000000-0000-4000-8000-000000000000")

        assertEquals(
            "dancesonic-ble-v1".length + 1 + 16,
            blePrologue(serviceUuid).size,
        )
        assertEquals(false, blePrologue(serviceUuid).contentEquals(blePrologue(other)))
    }
}
