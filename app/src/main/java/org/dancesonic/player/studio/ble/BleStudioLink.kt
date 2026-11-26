package org.dancesonic.player.studio.ble

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import org.dancesonic.player.studio.StudioLink
import org.dancesonic.player.studio.StudioMessage
import org.dancesonic.player.studio.StudioWire
import org.dancesonic.player.studio.noise.NoiseKeyPair
import org.dancesonic.player.studio.noise.NoiseTransport
import org.dancesonic.player.studio.noise.NoiseXxInitiator
import java.nio.ByteBuffer
import java.util.UUID

/** The GATT operations the Bluetooth link needs. The Android implementation is [AndroidBleTransport]. */
interface BleTransport {
    /** The ATT MTU negotiated for this connection. */
    val mtu: Int

    /** Notifications from Studio, one frame each. Closes when the connection drops. */
    val notifications: ReceiveChannel<ByteArray>

    /** Writes one frame to the phone-to-Studio characteristic. Returns once the write is acknowledged. */
    suspend fun write(frame: ByteArray)

    /** Disconnects. */
    fun close()
}

/** Bytes in a UUID. */
private const val UUID_BYTES = 16

/** The Noise prologue for a service UUID: the label, then the UUID's 16 bytes. Binds the session to the UUID. */
fun blePrologue(serviceUuid: UUID): ByteArray =
    "dancesonic-ble-v1".toByteArray(Charsets.US_ASCII) + byteArrayOf(0) +
        ByteBuffer
            .allocate(UUID_BYTES)
            .putLong(serviceUuid.mostSignificantBits)
            .putLong(serviceUuid.leastSignificantBits)
            .array()

/**
 * Opens a Bluetooth link to Studio: the Noise XX handshake as initiator, then Studio's `identity`, which the caller
 * checks against the fingerprint. Only after that does the link carry any protocol message.
 */
object BleStudioOpener {
    /** How long each step of the handshake may take. */
    const val STEP_TIMEOUT_MILLIS = 10_000L

    /**
     * Runs the handshake over [transport]. [isStudio] receives Studio's TLS public key (from `identity`) and its Noise
     * key (from the handshake); it must return true, or the connection is closed before anything else is sent.
     */
    @Suppress("LongParameterList", "ThrowsCount", "TooGenericExceptionCaught")
    suspend fun open(
        transport: BleTransport,
        serviceUuid: UUID,
        phone: NoiseKeyPair,
        scope: CoroutineScope,
        isStudio: (tlsSpki: ByteArray, noiseKey: ByteArray) -> Boolean,
        stepTimeoutMillis: Long = STEP_TIMEOUT_MILLIS,
    ): StudioLink {
        try {
            val initiator = NoiseXxInitiator(phone, blePrologue(serviceUuid))
            val reassembler = BleReassembler()
            send(transport, initiator.writeFirst())
            val second = receive(transport, reassembler, stepTimeoutMillis)
            initiator.readSecond(second)
            send(transport, initiator.writeThird())
            val noise = initiator.transport
            val identity = noise.decrypt(receive(transport, reassembler, stepTimeoutMillis))
            val tlsSpki = identityKey(identity)
            val noiseKey = initiator.remoteStatic ?: throw BleStudioException("Studio sent no static key")
            if (!isStudio(tlsSpki, noiseKey)) throw BleStudioException("the Studio on Bluetooth is not the one paired")
            return BleStudioLink(transport, noise, initiator.handshakeHash, scope)
        } catch (e: CancellationException) {
            transport.close()
            throw e
        } catch (e: BleStudioException) {
            transport.close()
            throw e
        } catch (e: Exception) {
            transport.close()
            throw BleStudioException("Bluetooth handshake failed", e)
        }
    }

    private suspend fun send(
        transport: BleTransport,
        message: ByteArray,
    ) {
        for (frame in BleFraming.frames(message, transport.mtu)) transport.write(frame)
    }

    private suspend fun receive(
        transport: BleTransport,
        reassembler: BleReassembler,
        timeoutMillis: Long,
    ): ByteArray =
        try {
            withTimeout(timeoutMillis) {
                var message: ByteArray? = null
                while (message == null) {
                    message = reassembler.push(transport.notifications.receive())
                }
                message
            }
        } catch (e: TimeoutCancellationException) {
            throw BleStudioException("Studio did not answer over Bluetooth in time", e)
        }

    /** Studio's first encrypted message: `{"type":"identity","tls_spki":"<base64url>"}`. */
    private fun identityKey(message: ByteArray): ByteArray {
        val parsed = StudioMessage.parse(message.toString(Charsets.UTF_8))
        val text =
            (parsed as? StudioMessage.Identity)?.tlsSpki
                ?: throw BleStudioException("Studio did not send its identity first")
        return StudioWire.decodeBase64Url(text, MAX_SPKI_BYTES)
            ?: throw BleStudioException("Studio's identity is malformed")
    }

    private const val MAX_SPKI_BYTES = 1024
}

/** A Studio session over Bluetooth. Frames are reassembled, decrypted and passed up as text, like the network link. */
class BleStudioLink internal constructor(
    private val transport: BleTransport,
    private val noise: NoiseTransport,
    override val binding: ByteArray,
    scope: CoroutineScope,
    /** Where the reader runs. Tests pass their own (#137). */
    io: CoroutineDispatcher = Dispatchers.IO,
) : StudioLink {
    override val incoming: Channel<String> = Channel(Channel.UNLIMITED)
    private val writeLock = Mutex()

    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private val reader: Job =
        scope.launch(io) {
            val reassembler = BleReassembler()
            try {
                while (true) {
                    val frame = transport.notifications.receive()
                    val sealed = reassembler.push(frame) ?: continue
                    incoming.send(noise.decrypt(sealed).toString(Charsets.UTF_8))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // A broken link ends the session: the reader stops and [incoming] closes below.
            } finally {
                incoming.close()
            }
        }

    override suspend fun send(text: String) {
        val sealed = noise.encrypt(text.toByteArray(Charsets.UTF_8))
        writeLock.withLock {
            for (frame in BleFraming.frames(sealed, transport.mtu)) transport.write(frame)
        }
    }

    override fun close() {
        reader.cancel()
        incoming.close()
        transport.close()
    }
}
