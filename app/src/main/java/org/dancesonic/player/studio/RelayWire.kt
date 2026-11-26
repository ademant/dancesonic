package org.dancesonic.player.studio

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Locale
import java.util.UUID

/**
 * Studio's public record on folksonic's relay, read from its `publicKey`:
 * `b64u(0x01 || noise_public (32) || BLE service UUID (16) || tls_spki)`. Only a claim: the server could have
 * replaced it. The Noise handshake and the comparison code show whether the Studio behind it holds these keys.
 */
class StudioRecord(
    val noisePublic: ByteArray,
    val bleService: UUID,
    val tlsSpki: ByteArray,
) {
    /** The fingerprint the record claims. */
    val fingerprint: ByteArray get() = StudioWire.fingerprint(tlsSpki, noisePublic)

    companion object {
        private const val VERSION: Byte = 1
        private const val NOISE_BYTES = 32
        private const val UUID_BYTES = 16
        private const val MAX_RECORD_BYTES = 768

        /** Parses `publicKey`; null for another version or a malformed value. */
        @Suppress("ReturnCount")
        fun parse(text: String): StudioRecord? {
            val bytes = StudioWire.decodeBase64Url(text, MAX_RECORD_BYTES) ?: return null
            if (bytes.isEmpty() || bytes[0] != VERSION || bytes.size <= 1 + NOISE_BYTES + UUID_BYTES) return null
            val uuid = ByteBuffer.wrap(bytes, 1 + NOISE_BYTES, UUID_BYTES)
            return StudioRecord(
                noisePublic = bytes.copyOfRange(1, 1 + NOISE_BYTES),
                bleService = UUID(uuid.long, uuid.long),
                tlsSpki = bytes.copyOfRange(1 + NOISE_BYTES + UUID_BYTES, bytes.size),
            )
        }
    }
}

private const val KIND_START: Byte = 1
private const val KIND_TRANSPORT: Byte = 2
private const val KIND_HANDSHAKE: Byte = 3

/** What one relay payload carries: the byte before the Noise message. */
enum class RelayKind(
    val byte: Byte,
) {
    /** The phone's first handshake message; it starts a session and replaces an older one. */
    START(KIND_START),

    /** A transport message. */
    TRANSPORT(KIND_TRANSPORT),

    /** A later handshake message, in either direction. */
    HANDSHAKE(KIND_HANDSHAKE),
}

/** A relay frame or chunk that breaks the format. Ends the session. */
class RelayFormatException(
    message: String,
) : Exception(message)

/**
 * The bytes of the folksonic relay transport in Studio's `docs/remote-protocol.md`: device ids, relay frames
 * `[id length][device id][payload]`, payloads `[kind][Noise message]`, chunk plaintexts `[flags][chunk]`, the Noise
 * prologue, and the pairing-by-code transcript and comparison code.
 */
object RelayWire {
    /** folksonic's limit for one relay message, header included. */
    const val MAX_RELAY_MESSAGE = 16 * 1024

    /** Bytes of protocol message per chunk; with the headers and the Noise tag a frame stays under the limit. */
    const val CHUNK = 15 * 1024

    /** The largest protocol message the phone accepts from Studio: library answers and covers are big. */
    const val MAX_INCOMING = 1024 * 1024

    /** The largest protocol message the phone sends. */
    const val MAX_OUTGOING = 64 * 1024

    private const val MAX_DEVICE_ID = 64
    private const val FIN: Byte = 1
    private const val MORE: Byte = 0
    private const val STUDIO_ID_HASH_BYTES = 12
    private const val PHONE_ID_BYTES = 12
    private const val CODE_BYTES = 4
    private const val CODE_MODULUS = 1_000_000L
    private const val BITS_PER_BYTE = 8
    private const val BYTE_MASK = 0xff

    private val prologueLabel = label("dancesonic-relay-v1")
    private val deviceIdLabel = label("dancesonic-relay-id-v1")
    private val pairCodeLabel = label("dancesonic-pair-code-v1")
    private val compareLabel = label("dancesonic-compare-v1")

    /** Studio's id on the relay, from its fingerprint: `"studio-" || b64u(SHA-256(label || fp)[0..12])`. */
    fun studioDeviceId(fingerprint: ByteArray): String =
        "studio-" + StudioWire.base64Url(sha256(deviceIdLabel, fingerprint).copyOf(STUDIO_ID_HASH_BYTES))

    /** A new random device id for this phone. */
    fun newPhoneId(random: SecureRandom = SecureRandom()): String =
        "phone-" + StudioWire.base64Url(ByteArray(PHONE_ID_BYTES).also { random.nextBytes(it) })

    /** Whether folksonic accepts [id]: 1 to 64 letters, digits, dots, dashes or underscores. */
    fun validDeviceId(id: String): Boolean =
        id.length in 1..MAX_DEVICE_ID && id.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it in ".-_" }

    /** The Noise prologue on the relay: binds the session to the Studio the phone meant to reach. */
    fun prologue(fingerprint: ByteArray): ByteArray = prologueLabel + fingerprint

    /** `[len][id][payload]`, the id being the recipient. */
    fun encodeFrame(
        to: String,
        payload: ByteArray,
    ): ByteArray {
        val id = to.toByteArray(Charsets.US_ASCII)
        if (!validDeviceId(to) || 1 + id.size + payload.size > MAX_RELAY_MESSAGE) {
            throw RelayFormatException("frame too large or bad id")
        }
        return byteArrayOf(id.size.toByte()) + id + payload
    }

    /** Splits a received frame into the sender's id and the payload. */
    fun decodeFrame(frame: ByteArray): Pair<String, ByteArray> {
        if (frame.isEmpty() || frame.size > MAX_RELAY_MESSAGE) throw RelayFormatException("frame size")
        val length = frame[0].toInt() and BYTE_MASK
        if (frame.size <= 1 + length) throw RelayFormatException("frame too short")
        val id = String(frame, 1, length, Charsets.US_ASCII)
        if (!validDeviceId(id)) throw RelayFormatException("bad sender id")
        return id to frame.copyOfRange(1 + length, frame.size)
    }

    /** `[kind][Noise message]`. */
    fun payload(
        kind: RelayKind,
        noiseMessage: ByteArray,
    ): ByteArray = byteArrayOf(kind.byte) + noiseMessage

    /** The Noise message of [payload], which must be of [kind]. */
    fun expect(
        payload: ByteArray,
        kind: RelayKind,
    ): ByteArray {
        if (payload.isEmpty() || payload[0] != kind.byte) throw RelayFormatException("unexpected payload kind")
        return payload.copyOfRange(1, payload.size)
    }

    /** Splits a protocol message into chunk plaintexts `[flags][chunk]`; the last one has `FIN` set. */
    fun chunks(message: ByteArray): List<ByteArray> {
        if (message.isEmpty()) return listOf(byteArrayOf(FIN))
        val parts = (message.indices step CHUNK).map { message.copyOfRange(it, minOf(it + CHUNK, message.size)) }
        return parts.mapIndexed { i, part -> byteArrayOf(if (i == parts.lastIndex) FIN else MORE) + part }
    }

    /** The bytes the `pair_code_request` signature covers. */
    fun pairCodeTranscript(
        fingerprint: ByteArray,
        deviceKey: ByteArray,
        noiseKey: ByteArray,
        binding: ByteArray,
    ): ByteArray = pairCodeLabel + fingerprint + deviceKey + noiseKey + binding

    /**
     * The 6-digit code both screens show when pairing through folksonic: the first 4 bytes of
     * `SHA-256("dancesonic-compare-v1\0" || binding)`, big-endian, mod 1 000 000. A server in the middle runs two
     * handshakes with different hashes, so the codes differ.
     */
    fun comparisonCode(binding: ByteArray): String {
        val hash = sha256(compareLabel, binding)
        var n = 0L
        for (i in 0 until CODE_BYTES) n = (n shl BITS_PER_BYTE) or (hash[i].toLong() and BYTE_MASK.toLong())
        return String.format(Locale.ROOT, "%06d", n % CODE_MODULUS)
    }

    private fun label(text: String): ByteArray = text.toByteArray(Charsets.US_ASCII) + byteArrayOf(0)

    private fun sha256(vararg parts: ByteArray): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        parts.forEach(digest::update)
        return digest.digest()
    }
}

/** Joins chunk plaintexts back into protocol messages of at most [max] bytes. */
class RelayJoiner(
    private val max: Int,
) {
    private val buffer = ByteArrayOutputStream()

    /** Adds one chunk plaintext; returns the message when it is complete. */
    fun push(plain: ByteArray): ByteArray? {
        if (plain.isEmpty()) throw RelayFormatException("empty chunk")
        val flags = plain[0].toInt()
        if (flags and FIN_MASK.inv() != 0 || buffer.size() + plain.size - 1 > max) {
            throw RelayFormatException("bad chunk")
        }
        buffer.write(plain, 1, plain.size - 1)
        if (flags and FIN_MASK == 0) return null
        return buffer.toByteArray().also { buffer.reset() }
    }

    private companion object {
        const val FIN_MASK = 1
    }
}
