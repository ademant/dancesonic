package org.dancesonic.player.studio

import org.dancesonic.player.subsonic.DeviceKeys
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.interfaces.ECPublicKey

/**
 * The phone's device key for one Studio. The private key stays where it is made: the Android Keystore in the app,
 * a software key in tests.
 */
interface StudioSigner {
    /** The 65-byte uncompressed P-256 point (0x04, x, y), as `device_key` on the wire. */
    fun devicePoint(): ByteArray

    /** An ECDSA P-256 signature with SHA-256 over [data], DER-encoded, as the protocol expects. */
    fun sign(data: ByteArray): ByteArray
}

/** A signer that uses a key already in the Keystore under [alias]. */
internal class KeystoreStudioSigner(
    private val alias: String,
) : StudioSigner {
    override fun devicePoint(): ByteArray {
        val key =
            DeviceKeys.keyStore().getCertificate(alias)?.publicKey
                ?: error("The device key is missing from the Keystore")
        return uncompressedPoint(key)
    }

    override fun sign(data: ByteArray): ByteArray {
        val signature = Signature.getInstance("SHA256withECDSA")
        signature.initSign(DeviceKeys.keyStore().getKey(alias, null) as PrivateKey)
        signature.update(data)
        return signature.sign()
    }
}

/** The uncompressed point of a P-256 public key: 0x04, then x and y, each 32 bytes big-endian. */
internal fun uncompressedPoint(key: PublicKey): ByteArray {
    val ec = key as? ECPublicKey ?: error("The device key is not an EC key")
    val x = fixedWidth(ec.w.affineX.toByteArray())
    val y = fixedWidth(ec.w.affineY.toByteArray())
    return byteArrayOf(UNCOMPRESSED_POINT_TAG) + x + y
}

private const val COORDINATE_BYTES = 32
private const val UNCOMPRESSED_POINT_TAG: Byte = 0x04

/** An unsigned big-endian value as exactly 32 bytes: a leading sign byte is dropped, short values are padded. */
private fun fixedWidth(value: ByteArray): ByteArray {
    val trimmed = value.dropWhile { it == 0.toByte() }.toByteArray()
    require(trimmed.size <= COORDINATE_BYTES) { "Coordinate is wider than P-256" }
    return ByteArray(COORDINATE_BYTES - trimmed.size) + trimmed
}
