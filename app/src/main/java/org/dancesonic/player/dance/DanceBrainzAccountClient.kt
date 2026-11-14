package org.dancesonic.player.dance

import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import org.dancesonic.player.account.refusalCodeOf
import org.dancesonic.player.subsonic.PublicJwk
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.HeaderMap
import retrofit2.http.POST
import retrofit2.http.Path

/**
 * The signed-in DanceBrainz calls: pairing this phone as a device, waiting for the owner's approval, checking a
 * credential, and revoking a token. A credential is sent only as its request headers (see [authHeaders]).
 */
class DanceBrainzAccountClient internal constructor(
    private val service: DanceBrainzAccountService,
) {
    constructor(baseUrl: HttpUrl, client: OkHttpClient = DanceBrainzClient.defaultHttpClient()) : this(
        DanceBrainzClient.retrofitFor(baseUrl, client).create(DanceBrainzAccountService::class.java),
    )

    /**
     * Sends the pairing [code] with the device's public key. The server answers 202 and keeps the device pending until
     * the owner approves it; no secret is returned.
     */
    suspend fun pairDevice(
        code: String,
        device: String,
        jwk: PublicJwk,
    ) {
        val response = danceBrainzCall { service.completeDevicePairing(DevicePairDto(code, device, jwk)) }
        if (response.code() != HTTP_ACCEPTED) throw refusal(response.code(), refusalCode(response))
    }

    /**
     * Polls until the owner approves the device with [credential]. Returns when it is approved. Throws when the
     * approval is refused, the device was removed, or [timeoutMillis] runs out.
     */
    suspend fun awaitApproval(
        credential: DanceBrainzCredential.Device,
        timeoutMillis: Long = APPROVAL_WAIT_MILLIS,
        pollMillis: Long = POLL_INTERVAL_MILLIS,
    ) {
        val deadline = System.nanoTime() + timeoutMillis * NANOS_PER_MILLI
        while (true) {
            val response = danceBrainzCall { service.deviceMe(credential.authHeaders()) }
            if (response.isSuccessful) return
            val code = refusalCode(response)
            if (code != PAIRING_PENDING) throw refusal(response.code(), code)
            if (System.nanoTime() >= deadline) {
                throw DanceBrainzException("The approval did not arrive in time. Pair this phone again.")
            }
            delay(pollMillis)
        }
    }

    /** Confirms that [credential] is accepted by the server. A revoked or wrong one is reported, not accepted. */
    suspend fun check(credential: DanceBrainzCredential) {
        danceBrainzCall { service.me(credential.authHeaders()) }
    }

    /** Revokes the token with [tokenId] on the server, sent with [credential]. */
    suspend fun revoke(
        credential: DanceBrainzCredential,
        tokenId: Long,
    ) {
        danceBrainzCall { service.revoke(tokenId, credential.authHeaders()) }
    }

    private fun refusal(
        status: Int,
        code: String?,
    ): DanceBrainzException =
        DanceBrainzException(
            when (code) {
                CODE_PAIRING_INVALID, CODE_PAIRING_EXPIRED -> INVALID_PAIRING_CODE
                CODE_DEVICE_UNKNOWN -> "The device was removed on the account page. Pair it again."
                CODE_PROOF_REPLAYED -> "DanceBrainz refused a repeated request. Try again."
                CODE_PROOF_INVALID -> "This phone's key was refused. Pair the phone again."
                CODE_DEVICE_EXISTS -> "This phone is already paired. Create a new pairing code on the account page."
                else -> "DanceBrainz answered with HTTP $status"
            },
            httpCode = status,
        )

    /** The refusal code in a failed response's body, nested as DanceBrainz sends it, or null when there is none. */
    private fun refusalCode(response: Response<*>): String? = refusalCodeOf(response.errorBody()?.string())

    companion object {
        private const val HTTP_ACCEPTED = 202
        private const val PAIRING_PENDING = "pairing_pending"
        private const val CODE_PAIRING_INVALID = "pairing_invalid"
        private const val CODE_PAIRING_EXPIRED = "pairing_expired"
        private const val CODE_DEVICE_UNKNOWN = "device_unknown"
        private const val CODE_PROOF_REPLAYED = "proof_replayed"
        private const val CODE_PROOF_INVALID = "proof_invalid"
        private const val CODE_DEVICE_EXISTS = "device_exists"
        private const val INVALID_PAIRING_CODE =
            "This pairing code is invalid or expired. Create a new one on the account page."

        /** A pairing code lasts five minutes on DanceBrainz, so the approval is awaited no longer than that. */
        const val APPROVAL_WAIT_MILLIS = 5L * 60_000L
        private const val POLL_INTERVAL_MILLIS = 3_000L
        private const val NANOS_PER_MILLI = 1_000_000L
    }
}

/** Retrofit endpoints of the signed-in DanceBrainz API, relative to the base URL. */
internal interface DanceBrainzAccountService {
    @POST("api/v1/pairings/complete")
    suspend fun completeDevicePairing(
        @Body body: DevicePairDto,
    ): Response<Void>

    /** Answers 200 once the device is approved; a refusal names why in its body. */
    @GET("api/v1/devices/me")
    suspend fun deviceMe(
        @HeaderMap auth: Map<String, String>,
    ): Response<Void>

    @GET("api/v1/me")
    suspend fun me(
        @HeaderMap auth: Map<String, String>,
    ): MeDto

    @DELETE("api/v1/tokens/{id}")
    suspend fun revoke(
        @Path("id") id: Long,
        @HeaderMap auth: Map<String, String>,
    )
}

/** The body of a pairing: the code from the account page, the device's name, and its public key. */
@Serializable
internal data class DevicePairDto(
    val token: String,
    val device: String,
    val jwk: PublicJwk,
) {
    override fun toString(): String = "DevicePairDto(device=$device, token=<redacted>)"
}

@Serializable
internal data class MeDto(
    val id: Long = 0,
    val role: String = "",
)
