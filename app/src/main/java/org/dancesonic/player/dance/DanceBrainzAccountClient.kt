package org.dancesonic.player.dance

import kotlinx.serialization.Serializable
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path

/**
 * The signed-in DanceBrainz calls: redeeming a pairing code, checking a token, and revoking one. Every call here
 * is the only place a token is sent, as a bearer header to the DanceBrainz server.
 */
class DanceBrainzAccountClient internal constructor(
    private val service: DanceBrainzAccountService,
) {
    constructor(baseUrl: HttpUrl, client: OkHttpClient = DanceBrainzClient.defaultHttpClient()) : this(
        DanceBrainzClient.retrofitFor(baseUrl, client).create(DanceBrainzAccountService::class.java),
    )

    /** Exchanges a one-time pairing [code] for a device token that never expires. */
    suspend fun redeem(
        code: String,
        device: String,
    ): DanceBrainzCredential {
        val answer = answerOrThrow { service.complete(PairCompleteDto(code, device)) }
        return DanceBrainzCredential(token = answer.token, tokenId = answer.id)
    }

    /** Confirms that [token] is accepted by the server. A revoked or wrong token is reported, not accepted. */
    suspend fun check(token: String) {
        answerOrThrow { service.me(bearer(token)) }
    }

    /** Revokes the token with [tokenId] on the server. */
    suspend fun revoke(
        token: String,
        tokenId: Long,
    ) {
        answerOrThrow { service.revoke(tokenId, bearer(token)) }
    }

    private fun bearer(token: String): String = "Bearer $token"

    private suspend fun <T> answerOrThrow(block: suspend () -> T): T = danceBrainzCall(block)
}

/** Retrofit endpoints of the signed-in DanceBrainz API, relative to the base URL. */
internal interface DanceBrainzAccountService {
    @POST("api/v1/pairings/complete")
    suspend fun complete(
        @Body body: PairCompleteDto,
    ): PairedTokenDto

    @GET("api/v1/me")
    suspend fun me(
        @Header("Authorization") bearer: String,
    ): MeDto

    @DELETE("api/v1/tokens/{id}")
    suspend fun revoke(
        @Path("id") id: Long,
        @Header("Authorization") bearer: String,
    )
}

@Serializable
internal data class PairCompleteDto(
    val token: String,
    val device: String,
) {
    override fun toString(): String = "PairCompleteDto(token=<redacted>, device=$device)"
}

@Serializable
internal data class PairedTokenDto(
    val token: String,
    val id: Long,
) {
    override fun toString(): String = "PairedTokenDto(token=<redacted>, id=$id)"
}

@Serializable
internal data class MeDto(
    val id: Long = 0,
    val role: String = "",
)
