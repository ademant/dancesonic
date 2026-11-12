package org.dancesonic.player.dance

import okhttp3.Interceptor
import okhttp3.Response
import org.dancesonic.player.subsonic.DeviceProof
import org.dancesonic.player.subsonic.DeviceSigner
import org.dancesonic.player.subsonic.KeystoreDeviceSigner

/**
 * A request header that names the device key to sign a request with. [DanceBrainzDeviceInterceptor] replaces it with
 * a DPoP proof and removes it, so the key name never leaves the phone.
 */
internal const val DEVICE_KEY_HEADER = "X-DanceSonic-Device"

private const val AUTHORIZATION = "Authorization"
private const val DPOP = "DPoP"

/**
 * The headers that identify the sender of one request: the bearer token, or the name of the device key. Empty for an
 * anonymous read, such as the lookups.
 */
internal fun DanceBrainzCredential?.authHeaders(): Map<String, String> =
    when (this) {
        null -> emptyMap()
        is DanceBrainzCredential.Token -> mapOf(AUTHORIZATION to "Bearer $token")
        is DanceBrainzCredential.Device -> mapOf(DEVICE_KEY_HEADER to alias)
    }

/**
 * Signs a request that names a device key with a DPoP proof (RFC 9449) for its method and its URL without the query,
 * as DanceBrainz verifies it (dancebrainz #227). Requests without the header pass unchanged.
 */
internal class DanceBrainzDeviceInterceptor(
    private val signerFor: (String) -> DeviceSigner = { KeystoreDeviceSigner(it) },
    private val nowSeconds: () -> Long = { System.currentTimeMillis() / MILLIS_PER_SECOND },
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val alias = request.header(DEVICE_KEY_HEADER) ?: return chain.proceed(request)
        val htu = request.url.newBuilder().query(null).fragment(null).build().toString()
        val proof = DeviceProof.build(signerFor(alias), request.method, htu, nowSeconds())
        val signed =
            request.newBuilder()
                .removeHeader(DEVICE_KEY_HEADER)
                .header(DPOP, proof)
                .build()
        return chain.proceed(signed)
    }

    private companion object {
        const val MILLIS_PER_SECOND = 1000L
    }
}
