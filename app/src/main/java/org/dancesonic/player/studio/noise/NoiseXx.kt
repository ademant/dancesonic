package org.dancesonic.player.studio.noise

import org.bouncycastle.crypto.InvalidCipherTextException
import org.bouncycastle.crypto.digests.Blake2sDigest
import org.bouncycastle.crypto.macs.HMac
import org.bouncycastle.crypto.modes.ChaCha20Poly1305
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.math.ec.rfc7748.X25519
import java.security.SecureRandom

/**
 * The Noise XX handshake with X25519, ChaCha20-Poly1305 and BLAKE2s (`Noise_XX_25519_ChaChaPoly_BLAKE2s`), as used by
 * DanceSonic Studio over Bluetooth (#24, #88). Only what the phone needs is here: the handshake, its transport
 * ciphers, and the handshake hash that binds a session to its connection.
 *
 * Every message is one value of this class's operations; the caller frames them for the Bluetooth link.
 */
internal const val NOISE_PROTOCOL_NAME = "Noise_XX_25519_ChaChaPoly_BLAKE2s"

/** A Curve25519 key pair. [privateKey] is the 32-byte scalar; [publicKey] the 32-byte u-coordinate. */
class NoiseKeyPair(
    val privateKey: ByteArray,
    val publicKey: ByteArray,
) {
    companion object {
        /** A fresh key pair. */
        fun generate(random: SecureRandom = SecureRandom()): NoiseKeyPair {
            val private = ByteArray(KEY_BYTES)
            X25519.generatePrivateKey(random, private)
            return fromPrivate(private)
        }

        /** The key pair of an existing private key. */
        fun fromPrivate(privateKey: ByteArray): NoiseKeyPair {
            require(privateKey.size == KEY_BYTES) { "an X25519 private key is $KEY_BYTES bytes" }
            val public = ByteArray(KEY_BYTES)
            X25519.generatePublicKey(privateKey, 0, public, 0)
            return NoiseKeyPair(privateKey.copyOf(), public)
        }
    }
}

/** Why a Noise message could not be read: wrong length, a bad tag, or a key that is not valid. */
class NoiseException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/** The transport ciphers after the handshake: [send] for messages the local side writes, [receive] for the peer's. */
class NoiseTransport internal constructor(
    private val send: CipherState,
    private val receive: CipherState,
) {
    /** Encrypts one message. The result is the ciphertext with its 16-byte tag. */
    fun encrypt(plain: ByteArray): ByteArray = send.encrypt(plain)

    /** Decrypts one message; throws [NoiseException] when the tag does not check. */
    fun decrypt(sealed: ByteArray): ByteArray = receive.decrypt(sealed)
}

/**
 * The initiator (the phone) of Noise XX. Call [writeFirst], [readSecond] and [writeThird] in that order; each returns
 * or reads one handshake message. [transport] is available afterwards, and [handshakeHash] is the channel binding.
 */
class NoiseXxInitiator(
    private val staticKey: NoiseKeyPair,
    prologue: ByteArray,
    private val random: SecureRandom = SecureRandom(),
) {
    private val state = HandshakeState(prologue)
    private var ephemeral: NoiseKeyPair? = null
    private var remoteEphemeral: ByteArray? = null
    private var remoteStaticKey: ByteArray? = null

    /** Studio's static public key, known after [readSecond]. */
    val remoteStatic: ByteArray? get() = remoteStaticKey

    /** `-> e`. */
    fun writeFirst(payload: ByteArray = ByteArray(0)): ByteArray {
        val e = NoiseKeyPair.generate(random).also { ephemeral = it }
        state.mixHash(e.publicKey)
        return e.publicKey + state.encryptAndHash(payload)
    }

    /** `<- e, ee, s, es`. Returns the payload Studio sent. */
    fun readSecond(message: ByteArray): ByteArray {
        val e = ephemeral ?: throw NoiseException("writeFirst must come first")
        if (message.size < KEY_BYTES + TAG_BYTES) throw NoiseException("the second message is too short")
        val re = message.copyOfRange(0, KEY_BYTES)
        remoteEphemeral = re
        state.mixHash(re)
        state.mixKey(dh(e.privateKey, re))
        val rs = state.decryptAndHash(message.copyOfRange(KEY_BYTES, KEY_BYTES + KEY_BYTES + TAG_BYTES))
        remoteStaticKey = rs
        state.mixKey(dh(e.privateKey, rs))
        return state.decryptAndHash(message.copyOfRange(KEY_BYTES + KEY_BYTES + TAG_BYTES, message.size))
    }

    /** `-> s, se`. After this the handshake is complete. */
    fun writeThird(payload: ByteArray = ByteArray(0)): ByteArray {
        val re = remoteEphemeral ?: throw NoiseException("readSecond must come first")
        val part = state.encryptAndHash(staticKey.publicKey)
        state.mixKey(dh(staticKey.privateKey, re))
        return part + state.encryptAndHash(payload)
    }

    /** The transport ciphers: [NoiseTransport.encrypt] for what the phone sends. Valid after [writeThird]. */
    val transport: NoiseTransport
        get() = transportCiphers ?: state.split(initiator = true).also { transportCiphers = it }

    private var transportCiphers: NoiseTransport? = null

    /** The handshake hash, the same value on both sides. Binds the session to this connection. */
    val handshakeHash: ByteArray get() = state.handshakeHash()
}

/** The responder side, used by tests to stand in for Studio. */
class NoiseXxResponder(
    private val staticKey: NoiseKeyPair,
    prologue: ByteArray,
    private val random: SecureRandom = SecureRandom(),
) {
    private val state = HandshakeState(prologue)
    private var ephemeral: NoiseKeyPair? = null
    private var remoteEphemeral: ByteArray? = null
    private var remoteStaticKey: ByteArray? = null

    /** The initiator's static public key, known after [readThird]. */
    val remoteStatic: ByteArray? get() = remoteStaticKey

    /** `-> e`, read. Returns the payload. */
    fun readFirst(message: ByteArray): ByteArray {
        if (message.size < KEY_BYTES) throw NoiseException("the first message is too short")
        val re = message.copyOfRange(0, KEY_BYTES)
        remoteEphemeral = re
        state.mixHash(re)
        return state.decryptAndHash(message.copyOfRange(KEY_BYTES, message.size))
    }

    /** `<- e, ee, s, es`. */
    fun writeSecond(payload: ByteArray = ByteArray(0)): ByteArray {
        val re = remoteEphemeral ?: throw NoiseException("readFirst must come first")
        val e = NoiseKeyPair.generate(random).also { ephemeral = it }
        state.mixHash(e.publicKey)
        state.mixKey(dh(e.privateKey, re))
        val s = state.encryptAndHash(staticKey.publicKey)
        state.mixKey(dh(staticKey.privateKey, re))
        return e.publicKey + s + state.encryptAndHash(payload)
    }

    /** `-> s, se`, read. Returns the payload. */
    fun readThird(message: ByteArray): ByteArray {
        val e = ephemeral ?: throw NoiseException("writeSecond must come first")
        if (message.size < KEY_BYTES + TAG_BYTES) throw NoiseException("the third message is too short")
        val rs = state.decryptAndHash(message.copyOfRange(0, KEY_BYTES + TAG_BYTES))
        remoteStaticKey = rs
        state.mixKey(dh(e.privateKey, rs))
        return state.decryptAndHash(message.copyOfRange(KEY_BYTES + TAG_BYTES, message.size))
    }

    /** The transport ciphers for what Studio sends. Valid after [readThird]. */
    val transport: NoiseTransport
        get() = transportCiphers ?: state.split(initiator = false).also { transportCiphers = it }

    private var transportCiphers: NoiseTransport? = null

    /** The handshake hash; equal to the initiator's. */
    val handshakeHash: ByteArray get() = state.handshakeHash()
}

internal class CipherState(
    private var key: ByteArray?,
) {
    private var nonce = 0L

    fun encrypt(
        plain: ByteArray,
        ad: ByteArray = ByteArray(0),
    ): ByteArray {
        val k = key ?: return plain
        val out = aead(encrypt = true, k, nonce, ad, plain)
        nonce++
        return out
    }

    fun decrypt(
        sealed: ByteArray,
        ad: ByteArray = ByteArray(0),
    ): ByteArray {
        val k = key ?: return sealed
        val out = aead(encrypt = false, k, nonce, ad, sealed)
        nonce++
        return out
    }

    fun setKey(newKey: ByteArray?) {
        key = newKey
        nonce = 0L
    }

    private fun aead(
        encrypt: Boolean,
        k: ByteArray,
        n: Long,
        ad: ByteArray,
        input: ByteArray,
    ): ByteArray {
        val cipher = ChaCha20Poly1305()
        cipher.init(encrypt, AEADParameters(KeyParameter(k), TAG_BITS, nonceBytes(n), ad))
        val out = ByteArray(cipher.getOutputSize(input.size))
        val len = cipher.processBytes(input, 0, input.size, out, 0)
        return try {
            out.copyOf(len + cipher.doFinal(out, len))
        } catch (e: InvalidCipherTextException) {
            throw NoiseException("a message failed its check", e)
        }
    }

    /** Noise's nonce for ChaCha20-Poly1305: four zero bytes, then the counter little-endian. */
    private fun nonceBytes(n: Long): ByteArray {
        val out = ByteArray(NONCE_BYTES)
        for (i in 0 until COUNTER_BYTES) out[NONCE_PREFIX_BYTES + i] = (n ushr (BITS_PER_BYTE * i)).toByte()
        return out
    }
}

private class SymmetricState {
    private var chainingKey: ByteArray
    private var hash: ByteArray
    private val cipher = CipherState(null)

    init {
        val name = NOISE_PROTOCOL_NAME.toByteArray(Charsets.US_ASCII)
        hash = if (name.size <= HASH_BYTES) name.copyOf(HASH_BYTES) else hashOf(name)
        chainingKey = hash.copyOf()
    }

    fun mixHash(data: ByteArray) {
        hash = hashOf(hash + data)
    }

    fun mixKey(inputKeyMaterial: ByteArray) {
        val (ck, temp) = hkdf2(chainingKey, inputKeyMaterial)
        chainingKey = ck
        cipher.setKey(temp)
    }

    fun encryptAndHash(plain: ByteArray): ByteArray {
        val out = cipher.encrypt(plain, hash)
        mixHash(out)
        return out
    }

    fun decryptAndHash(sealed: ByteArray): ByteArray {
        val out = cipher.decrypt(sealed, hash)
        mixHash(sealed)
        return out
    }

    fun handshakeHash(): ByteArray = hash.copyOf()

    /** The two transport ciphers: the first is for initiator-to-responder messages. */
    fun split(initiator: Boolean): NoiseTransport {
        val (k1, k2) = hkdf2(chainingKey, ByteArray(0))
        val first = CipherState(k1)
        val second = CipherState(k2)
        return if (initiator) NoiseTransport(first, second) else NoiseTransport(second, first)
    }
}

/** The handshake state shared by both roles: the symmetric state and the pre-message prologue. */
private class HandshakeState(
    prologue: ByteArray,
) {
    private val symmetric = SymmetricState()

    init {
        symmetric.mixHash(prologue)
    }

    fun mixHash(data: ByteArray) = symmetric.mixHash(data)

    fun mixKey(input: ByteArray) = symmetric.mixKey(input)

    fun encryptAndHash(plain: ByteArray): ByteArray = symmetric.encryptAndHash(plain)

    fun decryptAndHash(sealed: ByteArray): ByteArray = symmetric.decryptAndHash(sealed)

    fun handshakeHash(): ByteArray = symmetric.handshakeHash()

    fun split(initiator: Boolean): NoiseTransport = symmetric.split(initiator)
}

private fun dh(
    privateKey: ByteArray,
    publicKey: ByteArray,
): ByteArray {
    val out = ByteArray(KEY_BYTES)
    if (!X25519.calculateAgreement(privateKey, 0, publicKey, 0, out, 0)) {
        throw NoiseException("a Diffie-Hellman key is not valid")
    }
    return out
}

private fun hashOf(data: ByteArray): ByteArray {
    val digest = Blake2sDigest(HASH_BITS)
    digest.update(data, 0, data.size)
    return ByteArray(HASH_BYTES).also { digest.doFinal(it, 0) }
}

private fun hmac(
    key: ByteArray,
    data: ByteArray,
): ByteArray {
    val mac = HMac(Blake2sDigest(HASH_BITS))
    mac.init(KeyParameter(key))
    mac.update(data, 0, data.size)
    return ByteArray(HASH_BYTES).also { mac.doFinal(it, 0) }
}

/** Noise's HKDF with two outputs, as the spec defines it over HMAC-BLAKE2s. */
private fun hkdf2(
    chainingKey: ByteArray,
    input: ByteArray,
): Pair<ByteArray, ByteArray> {
    val temp = hmac(chainingKey, input)
    val out1 = hmac(temp, byteArrayOf(0x01))
    val out2 = hmac(temp, out1 + byteArrayOf(0x02))
    return out1 to out2
}

private const val KEY_BYTES = 32
private const val HASH_BYTES = 32
private const val TAG_BYTES = 16
private const val TAG_BITS = 128
private const val NONCE_BYTES = 12
private const val NONCE_PREFIX_BYTES = 4
private const val COUNTER_BYTES = 8
private const val BITS_PER_BYTE = 8
private const val HASH_BITS = 256
