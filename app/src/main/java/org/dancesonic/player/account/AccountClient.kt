package org.dancesonic.player.account

import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.Response
import org.dancesonic.player.subsonic.SubsonicHttp
import java.io.IOException

/**
 * Sign-in endpoints of a folksonic/tinysonic server. These are not part of the Subsonic API.
 * The requests carry no API key: they are how a key is obtained.
 */
internal interface AccountService {
    @POST("api/login")
    suspend fun login(@Body body: LoginRequest): LoginResponse

    @POST("api/keys")
    suspend fun createApiKey(
        @Header("Authorization") bearer: String,
        @Body body: CreateKeyRequest,
    ): CreateKeyResponse

    @POST("api/logout")
    suspend fun logout(@Header("Authorization") bearer: String): Response<Unit>

    @POST("api/pair/complete")
    suspend fun completePairing(@Body body: PairCompleteRequest): PairCompleteResponse

    /** Keys can be listed and revoked with an API key, so the app needs no session for this. */
    @GET("api/keys")
    suspend fun listKeys(@Query("apiKey") apiKey: String): ListKeysResponse

    @DELETE("api/keys/{id}")
    suspend fun revokeKey(@Path("id") id: Long, @Query("apiKey") apiKey: String): Response<Unit>
}

@Serializable
internal data class ListKeysResponse(
    val keys: List<KeyInfo> = emptyList(),
)

/** One key as the server lists it. `prefix` is the start of the secret, shown for identification. */
@Serializable
internal data class KeyInfo(
    val id: Long,
    val prefix: String,
)

@Serializable
internal data class LoginRequest(
    val username: String,
    val password: String,
) {
    override fun toString(): String = "LoginRequest(username=$username, password=<redacted>)"
}

@Serializable
internal data class LoginResponse(
    val token: String,
    val username: String? = null,
)

@Serializable
internal data class CreateKeyRequest(
    val label: String,
    /** 0 means the key does not expire. */
    @SerialName("expires_days") val expiresDays: Int,
)

@Serializable
internal data class CreateKeyResponse(
    val apiKey: String,
)

@Serializable
internal data class PairCompleteRequest(
    val token: String,
    val device: String,
) {
    override fun toString(): String = "PairCompleteRequest(device=$device, token=<redacted>)"
}

@Serializable
internal data class PairCompleteResponse(
    val apiKey: String,
    val username: String? = null,
)

/** A failure the user can act on. The message is safe to show on screen. */
class AccountException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Obtains API keys from a folksonic server, either by signing in with a password or by redeeming
 * a pairing token from the QR code. The app stores only the resulting API key.
 */
class AccountClient internal constructor(private val http: OkHttpClient) {

    constructor() : this(SubsonicHttp.okHttpClient { null })

    /** Signs in with a password and returns a short-lived session token. */
    suspend fun login(server: HttpUrl, username: String, password: String): String =
        call(unauthorized = "Wrong username or password.") {
            service(server).login(LoginRequest(username, password)).token
        }

    /** Creates a non-expiring API key for this app using a session token. */
    suspend fun createApiKey(server: HttpUrl, sessionToken: String): String =
        call(unauthorized = "Your sign-in expired. Please try again.") {
            service(server).createApiKey("Bearer $sessionToken", CreateKeyRequest(DEVICE_LABEL, expiresDays = 0)).apiKey
        }

    /**
     * Ends the session on the server. Callers use this after the API key is obtained, so a failure
     * here does not affect the connection. The session also expires on its own.
     */
    suspend fun logout(server: HttpUrl, sessionToken: String) {
        call(unauthorized = "Your sign-in already expired.") {
            val response = service(server).logout("Bearer $sessionToken")
            if (!response.isSuccessful) throw HttpException(response)
        }
    }

    /** Redeems a single-use pairing token from the QR code for an API key. */
    suspend fun redeemPairing(request: PairingRequest): String =
        call(unauthorized = INVALID_PAIRING_CODE) {
            service(request.server).completePairing(PairCompleteRequest(request.token, DEVICE_LABEL)).apiKey
        }

    /**
     * Revokes this app's key on the server. Returns false when the key is no longer listed, which
     * means it was already revoked. The key is matched by its prefix; if the prefix is ambiguous
     * nothing is revoked, so no other key is touched.
     */
    suspend fun revokeOwnKey(server: HttpUrl, apiKey: String): Boolean {
        val keys = call(unauthorized = "The stored key is no longer valid.") {
            service(server).listKeys(apiKey).keys
        }
        val id = matchOwnKeyId(keys, apiKey) ?: return false
        call(unauthorized = "The stored key is no longer valid.") {
            val response = service(server).revokeKey(id, apiKey)
            if (!response.isSuccessful) throw HttpException(response)
        }
        return true
    }

    private fun service(server: HttpUrl): AccountService =
        Retrofit.Builder()
            .baseUrl(server)
            .client(http)
            .addConverterFactory(accountJson.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(AccountService::class.java)

    private suspend fun <T> call(unauthorized: String, block: suspend () -> T): T =
        try {
            block()
        } catch (e: HttpException) {
            when (e.code()) {
                401 -> throw AccountException(unauthorized, e)
                else -> throw AccountException("The server returned HTTP ${e.code()}.", e)
            }
        } catch (e: SerializationException) {
            throw AccountException("The server sent an unexpected response.", e)
        } catch (e: IOException) {
            throw AccountException("Could not reach the server.", e)
        }

    companion object {
        const val DEVICE_LABEL = "DanceSonic Android"

        /** The id of the key whose prefix starts [apiKey], or null if zero or several match. */
        internal fun matchOwnKeyId(keys: List<KeyInfo>, apiKey: String): Long? {
            val matches = keys.filter { it.prefix.isNotEmpty() && apiKey.startsWith(it.prefix) }
            return matches.singleOrNull()?.id
        }
        const val INVALID_PAIRING_CODE =
            "This pairing code is invalid or expired. Create a new one on the account page."

        private val accountJson = Json {
            ignoreUnknownKeys = true
        }
    }
}
