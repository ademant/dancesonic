package org.dancesonic.player.subsonic

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Interceptor
import okhttp3.Response
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import java.util.UUID

/**
 * A public P-256 key in JWK form (RFC 7517): the key a paired device proves itself with. folksonic keeps only its
 * thumbprint. [kty] and [crv] are always `EC` and `P-256`, and are sent explicitly, because kotlinx omits defaults.
 */
@Serializable
data class PublicJwk(
    val kty: String,
    val crv: String,
    val x: String,
    val y: String,
) {
    companion object {
        /** A P-256 key from its affine coordinates, each 32 bytes big-endian. */
        fun of(
            x: BigInteger,
            y: BigInteger,
        ): PublicJwk = PublicJwk(kty = "EC", crv = "P-256", x = base64Url(fixedWidth(x)), y = base64Url(fixedWidth(y)))
    }
}

/** Signs with a device key. The Keystore implementation keeps the private key inside the Keystore. */
interface DeviceSigner {
    /** The public key, which the server keeps only as a thumbprint. */
    fun publicJwk(): PublicJwk

    /** An ES256 signature of [data] in JWS form: 64 bytes, r then s. */
    fun sign(data: ByteArray): ByteArray
}

/**
 * A DPoP proof (RFC 9449) as folksonic verifies it (`internal/dpop`): an ES256 JWS with `typ` `dpop+jwt`, the public
 * key in the header, and the claims `jti`, `htm` (the method), `htu` (the URL without its query) and `iat` (seconds).
 */
object DeviceProof {
    /** Builds the proof for one request. [htu] must be the request URL without its query, as the server sees it. */
    fun build(
        signer: DeviceSigner,
        method: String,
        htu: String,
        nowSeconds: Long,
        jti: String = UUID.randomUUID().toString(),
    ): String {
        val jwk = signer.publicJwk()
        val header =
            buildJsonObject {
                put("typ", "dpop+jwt")
                put("alg", "ES256")
                put(
                    "jwk",
                    buildJsonObject {
                        put("kty", jwk.kty)
                        put("crv", jwk.crv)
                        put("x", jwk.x)
                        put("y", jwk.y)
                    },
                )
            }
        val claims =
            buildJsonObject {
                put("jti", jti)
                put("htm", method)
                put("htu", htu)
                put("iat", nowSeconds)
            }
        val signingInput =
            base64Url(header.toString().toByteArray(Charsets.UTF_8)) + "." +
                base64Url(claims.toString().toByteArray(Charsets.UTF_8))
        return signingInput + "." + base64Url(signer.sign(signingInput.toByteArray(Charsets.US_ASCII)))
    }
}

/** Adds a `DPoP` proof to every request to a paired device's server. Requests to other hosts are left alone. */
internal class DeviceProofInterceptor(
    private val settingsProvider: () -> ServerSettings?,
    private val signerFor: (String) -> DeviceSigner = { KeystoreDeviceSigner(it) },
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val settings = settingsProvider()
        val device = settings?.auth as? ServerAuth.Device
        if (settings == null || device == null || !isUnder(request.url, settings.baseUrl)) {
            return chain.proceed(request)
        }
        // The server builds the URL it checks from base_url and the path, so the query and fragment are left out.
        val htu = request.url.newBuilder().query(null).fragment(null).build().toString()
        val proof =
            DeviceProof.build(
                signer = signerFor(device.alias),
                method = request.method,
                htu = htu,
                nowSeconds = System.currentTimeMillis() / MILLIS_PER_SECOND,
            )
        return chain.proceed(request.newBuilder().header(DPOP_HEADER, proof).build())
    }

    private fun isUnder(
        url: okhttp3.HttpUrl,
        server: okhttp3.HttpUrl,
    ): Boolean =
        url.scheme == server.scheme &&
            url.host == server.host &&
            url.port == server.port &&
            url.encodedPath.startsWith(server.encodedPath)

    companion object {
        const val DPOP_HEADER = "DPoP"
        private const val MILLIS_PER_SECOND = 1_000L
    }
}

/** The device keys in the Android Keystore. Each pairing gets a new key, so a key is never paired twice. */
internal object DeviceKeys {
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val ALIAS_PREFIX = "dancesonic.device."

    /** Creates a new P-256 signing key and returns its alias. The private key never leaves the Keystore. */
    fun create(): String {
        val alias = ALIAS_PREFIX + UUID.randomUUID()
        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEYSTORE).apply {
            initialize(
                KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
                    .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                    .setDigests(KeyProperties.DIGEST_SHA256)
                    .build(),
            )
            generateKeyPair()
        }
        return alias
    }

    /** Removes a device key. Its pairing on the server is removed on the account page. */
    fun delete(alias: String) {
        keyStore().deleteEntry(alias)
    }

    internal fun keyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
}

/** A device key in the Keystore, read by its alias. */
internal class KeystoreDeviceSigner(private val alias: String) : DeviceSigner {
    override fun publicJwk(): PublicJwk {
        val key =
            DeviceKeys.keyStore().getCertificate(alias)?.publicKey as? ECPublicKey
                ?: error("The device key is missing from the Keystore")
        return PublicJwk.of(key.w.affineX, key.w.affineY)
    }

    override fun sign(data: ByteArray): ByteArray {
        val signature = Signature.getInstance("SHA256withECDSA")
        signature.initSign(DeviceKeys.keyStore().getKey(alias, null) as PrivateKey)
        signature.update(data)
        return derToRaw(signature.sign())
    }
}

/** Converts an ECDSA signature from DER (what the Keystore returns) to the raw r‖s form JWS uses. */
internal fun derToRaw(der: ByteArray): ByteArray {
    require(der.isNotEmpty() && der[0] == SEQUENCE_TAG) { "Signature is not DER" }
    val (_, afterSequence) = readLength(der, 1)
    val (r, afterR) = readInteger(der, afterSequence)
    val (s, _) = readInteger(der, afterR)
    return fixedWidth(r) + fixedWidth(s)
}

private const val SEQUENCE_TAG: Byte = 0x30
private const val INTEGER_TAG: Byte = 0x02
private const val SHORT_LENGTH_LIMIT = 0x80
private const val LONG_LENGTH_MASK = 0x7f
private const val COORDINATE_BYTES = 32
private const val BYTE_MASK = 0xff
private const val BITS_PER_BYTE = 8

/** A length at [pos], and the position after it. */
private fun readLength(
    der: ByteArray,
    pos: Int,
): Pair<Int, Int> {
    val first = der[pos].toInt() and BYTE_MASK
    if (first < SHORT_LENGTH_LIMIT) return first to pos + 1
    val count = first and LONG_LENGTH_MASK
    var length = 0
    for (i in 0 until count) {
        length = (length shl BITS_PER_BYTE) or (der[pos + 1 + i].toInt() and BYTE_MASK)
    }
    return length to pos + 1 + count
}

/** An INTEGER at [pos], as its unsigned value, and the position after it. */
private fun readInteger(
    der: ByteArray,
    pos: Int,
): Pair<BigInteger, Int> {
    require(der[pos] == INTEGER_TAG) { "Signature is not DER" }
    val (length, start) = readLength(der, pos + 1)
    return BigInteger(1, der.copyOfRange(start, start + length)) to start + length
}

/** The unsigned value as exactly 32 big-endian bytes: leading zeros dropped, then padded to the width. */
private fun fixedWidth(value: ByteArray): ByteArray {
    val trimmed = value.dropWhile { it == 0.toByte() }.toByteArray()
    require(trimmed.size <= COORDINATE_BYTES) { "Coordinate is wider than P-256" }
    return ByteArray(COORDINATE_BYTES - trimmed.size) + trimmed
}

private fun fixedWidth(value: BigInteger): ByteArray = fixedWidth(value.toByteArray())

private fun base64Url(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
