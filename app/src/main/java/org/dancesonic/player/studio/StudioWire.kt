package org.dancesonic.player.studio

import java.security.MessageDigest
import java.util.Base64

/**
 * The bytes of `docs/remote-protocol.md` that the phone computes and checks: Studio's fingerprint, the mDNS tag, the
 * pairing transcript, and the signed input of `hello` and of each command. Every label ends with a zero byte.
 */
object StudioWire {
    /** The protocol version this app speaks. */
    const val PROTOCOL_VERSION = 1

    /** The TLS exporter label of the channel binding (network transport). */
    const val EXPORTER_LABEL = "EXPORTER-dancesonic-studio-v1"

    /** Length of the channel binding, in bytes. */
    const val BINDING_BYTES = 32

    /** Length of an uncompressed P-256 point: 0x04, then x and y. */
    const val DEVICE_KEY_BYTES = 65

    /** Length of the X25519 public key sent as `noise_key`. */
    const val NOISE_KEY_BYTES = 32

    private val fingerprintLabel = "dancesonic-studio-id-v1\u0000".toByteArray(Charsets.US_ASCII)
    private val mdnsLabel = "dancesonic-mdns-v1\u0000".toByteArray(Charsets.US_ASCII)
    private val pairLabel = "dancesonic-pair-v1\u0000".toByteArray(Charsets.US_ASCII)
    private val helloLabel = "dancesonic-hello-v1\u0000".toByteArray(Charsets.US_ASCII)
    private val commandLabel = "dancesonic-cmd-v1\u0000".toByteArray(Charsets.US_ASCII)

    private const val SHA256_OUTPUT_BYTES = 32
    private const val MDNS_TAG_BYTES = 8
    private const val COUNTER_BYTES = 8
    private const val BITS_PER_BYTE = 8
    private const val BYTE_MASK = 0xff

    /** `fp = SHA-256("dancesonic-studio-id-v1\0" || SPKI_DER(TLS key) || noise_public)`. */
    fun fingerprint(
        tlsSpki: ByteArray,
        noisePublic: ByteArray,
    ): ByteArray = sha256(fingerprintLabel, tlsSpki, noisePublic)

    /** The mDNS TXT tag of a fingerprint: the first 8 bytes of a labelled hash, base64url. */
    fun mdnsTag(fingerprint: ByteArray): String = base64Url(sha256(mdnsLabel, fingerprint).copyOf(MDNS_TAG_BYTES))

    /** The bytes a pairing proof and the pairing signature cover. */
    fun pairTranscript(
        fingerprint: ByteArray,
        deviceKey: ByteArray,
        noiseKey: ByteArray,
        binding: ByteArray,
    ): ByteArray = pairLabel + fingerprint + deviceKey + noiseKey + binding

    /** The bytes the `hello` signature covers. */
    fun helloBytes(
        deviceId: String,
        counter: Long,
        binding: ByteArray,
    ): ByteArray = helloLabel + deviceId.toByteArray(Charsets.UTF_8) + u64(counter) + binding

    /** The bytes a command signature covers: the exact UTF-8 text of the body. */
    fun commandBytes(
        deviceId: String,
        counter: Long,
        body: String,
    ): ByteArray = commandLabel + deviceId.toByteArray(Charsets.UTF_8) + u64(counter) + body.toByteArray(Charsets.UTF_8)

    /** base64url without padding, as the protocol writes every binary value. */
    fun base64Url(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    /** Decodes base64url text of at most [maxBytes] bytes, or null when it is not valid or too long. */
    fun decodeBase64Url(
        text: String,
        maxBytes: Int,
    ): ByteArray? =
        runCatching { Base64.getUrlDecoder().decode(text) }
            .getOrNull()
            ?.takeIf { it.size <= maxBytes }

    /** An 8-byte big-endian unsigned counter. */
    fun u64(value: Long): ByteArray =
        ByteArray(
            COUNTER_BYTES,
        ) { i -> (value ushr ((COUNTER_BYTES - 1 - i) * BITS_PER_BYTE) and BYTE_MASK.toLong()).toByte() }

    private fun sha256(vararg parts: ByteArray): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        parts.forEach(digest::update)
        return digest.digest().also { check(it.size == SHA256_OUTPUT_BYTES) }
    }
}
