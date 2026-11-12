package org.dancesonic.player.testsupport

import org.dancesonic.player.subsonic.DeviceSigner
import org.dancesonic.player.subsonic.PublicJwk
import org.dancesonic.player.subsonic.derToRaw
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec

/** A device key held in memory, for tests. The Android Keystore is not available under Robolectric. */
class SoftwareDeviceSigner : DeviceSigner {
    private val pair: KeyPair =
        KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()

    override fun publicJwk(): PublicJwk {
        val key = pair.public as ECPublicKey
        return PublicJwk.of(key.w.affineX, key.w.affineY)
    }

    override fun sign(data: ByteArray): ByteArray {
        val signature = Signature.getInstance("SHA256withECDSA")
        signature.initSign(pair.private)
        signature.update(data)
        return derToRaw(signature.sign())
    }

    /** Checks a raw r‖s signature the way a verifier of JWS ES256 does, by converting it back to DER. */
    fun verifies(
        data: ByteArray,
        raw: ByteArray,
    ): Boolean {
        val verifier = Signature.getInstance("SHA256withECDSA")
        verifier.initVerify(pair.public)
        verifier.update(data)
        return verifier.verify(rawToDer(raw))
    }

    private fun rawToDer(raw: ByteArray): ByteArray {
        require(raw.size == RAW_SIZE) { "Raw signatures are 64 bytes" }
        val r = derInteger(raw.copyOfRange(0, RAW_SIZE / 2))
        val s = derInteger(raw.copyOfRange(RAW_SIZE / 2, RAW_SIZE))
        val body = r + s
        return byteArrayOf(0x30, body.size.toByte()) + body
    }

    private fun derInteger(bytes: ByteArray): ByteArray {
        val trimmed: ByteArray = bytes.dropWhile { it == 0.toByte() }.toByteArray()
        val magnitude: ByteArray = if (trimmed.isEmpty()) byteArrayOf(0) else trimmed
        // A DER INTEGER is signed: a set high bit needs a leading zero byte.
        val value: ByteArray = if (magnitude[0] < 0) byteArrayOf(0) + magnitude else magnitude
        return byteArrayOf(0x02, value.size.toByte()) + value
    }

    private companion object {
        const val RAW_SIZE = 64
    }
}
