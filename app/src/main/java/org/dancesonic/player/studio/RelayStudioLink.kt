package org.dancesonic.player.studio

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import org.dancesonic.player.studio.noise.NoiseKeyPair
import org.dancesonic.player.studio.noise.NoiseTransport
import org.dancesonic.player.studio.noise.NoiseXxInitiator

/** The relay link could not be opened. [notThisStudio] is set when the Studio that answered is not the one meant. */
class RelayStudioException(
    message: String,
    val notThisStudio: Boolean = false,
    cause: Throwable? = null,
) : Exception(message, cause)

/**
 * Opens a link to Studio through folksonic's relay: the Noise XX handshake as initiator, with the prologue bound to
 * the fingerprint the phone means to reach, then Studio's `identity`, which the caller checks. folksonic sees only
 * ciphertext; a tampered, replayed, reordered or missing frame fails Noise and ends the link.
 */
object RelayStudioOpener {
    /** How long each step of the handshake may take. */
    const val STEP_TIMEOUT_MILLIS = 10_000L

    private const val MAX_SPKI_BYTES = 1024

    /**
     * Runs the handshake over [pipe]. [isStudio] receives Studio's TLS public key and its Noise key; it must return
     * true, or the link is closed before anything else is sent.
     */
    @Suppress("LongParameterList", "ThrowsCount", "TooGenericExceptionCaught")
    suspend fun open(
        pipe: RelayPipe,
        fingerprint: ByteArray,
        phone: NoiseKeyPair,
        scope: CoroutineScope,
        isStudio: (tlsSpki: ByteArray, noiseKey: ByteArray) -> Boolean,
        stepTimeoutMillis: Long = STEP_TIMEOUT_MILLIS,
    ): StudioLink {
        try {
            val initiator = NoiseXxInitiator(phone, RelayWire.prologue(fingerprint))
            pipe.send(RelayWire.payload(RelayKind.START, initiator.writeFirst()))
            initiator.readSecond(RelayWire.expect(receive(pipe, stepTimeoutMillis), RelayKind.HANDSHAKE))
            pipe.send(RelayWire.payload(RelayKind.HANDSHAKE, initiator.writeThird()))
            val noise = initiator.transport
            val identity = receiveMessage(pipe, noise, stepTimeoutMillis)
            val noiseKey = initiator.remoteStatic ?: throw RelayStudioException("Studio sent no static key")
            if (!isStudio(identityKey(identity), noiseKey)) {
                throw RelayStudioException("the Studio on the relay is not the one meant", notThisStudio = true)
            }
            return RelayStudioLink(pipe, noise, initiator.handshakeHash, scope)
        } catch (e: CancellationException) {
            pipe.close()
            throw e
        } catch (e: RelayStudioException) {
            pipe.close()
            throw e
        } catch (e: Exception) {
            pipe.close()
            throw RelayStudioException("relay handshake failed", cause = e)
        }
    }

    /** One whole protocol message, joined from its chunks; Studio's `identity` is chunked like any other. */
    private suspend fun receiveMessage(
        pipe: RelayPipe,
        noise: NoiseTransport,
        timeoutMillis: Long,
    ): ByteArray {
        val joiner = RelayJoiner(RelayWire.MAX_INCOMING)
        while (true) {
            val sealed = RelayWire.expect(receive(pipe, timeoutMillis), RelayKind.TRANSPORT)
            joiner.push(noise.decrypt(sealed))?.let { return it }
        }
    }

    private suspend fun receive(
        pipe: RelayPipe,
        timeoutMillis: Long,
    ): ByteArray =
        try {
            withTimeout(timeoutMillis) { pipe.incoming.receive() }
        } catch (e: TimeoutCancellationException) {
            throw RelayStudioException("Studio did not answer through the relay in time", cause = e)
        }

    /** Studio's first message: `{"type":"identity","tls_spki":"<base64url>"}`. */
    private fun identityKey(message: ByteArray): ByteArray {
        val parsed = StudioMessage.parse(message.toString(Charsets.UTF_8)) as? StudioMessage.Identity
        val text = parsed?.tlsSpki ?: throw RelayStudioException("Studio did not send its identity first")
        return StudioWire.decodeBase64Url(text, MAX_SPKI_BYTES)
            ?: throw RelayStudioException("Studio's identity is malformed")
    }
}

/**
 * A Studio session through the relay. Each protocol message is split into chunks, each chunk is one Noise transport
 * message in one relay frame. Received chunks are decrypted in order and joined back into text.
 */
class RelayStudioLink internal constructor(
    private val pipe: RelayPipe,
    private val noise: NoiseTransport,
    override val binding: ByteArray,
    scope: CoroutineScope,
) : StudioLink {
    override val incoming: Channel<String> = Channel(Channel.UNLIMITED)
    private val writeLock = Mutex()

    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private val reader: Job =
        scope.launch(Dispatchers.IO) {
            val joiner = RelayJoiner(RelayWire.MAX_INCOMING)
            try {
                for (payload in pipe.incoming) {
                    // Noise counts its nonces, so a replayed, reordered or changed frame fails to decrypt here.
                    val plain = noise.decrypt(RelayWire.expect(payload, RelayKind.TRANSPORT))
                    val message = joiner.push(plain) ?: continue
                    incoming.send(message.toString(Charsets.UTF_8))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // A broken frame ends the session: the reader stops and [incoming] closes below.
            } finally {
                incoming.close()
                pipe.close()
            }
        }

    override suspend fun send(text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        if (bytes.size > RelayWire.MAX_OUTGOING) throw RelayFormatException("message too large for the relay")
        writeLock.withLock {
            for (chunk in RelayWire.chunks(bytes)) {
                pipe.send(RelayWire.payload(RelayKind.TRANSPORT, noise.encrypt(chunk)))
            }
        }
    }

    override fun close() {
        reader.cancel()
        incoming.close()
        pipe.close()
    }
}
