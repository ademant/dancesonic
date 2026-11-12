package org.dancesonic.player.account

import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.coroutines.delay
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import org.dancesonic.player.subsonic.PublicJwk
import org.dancesonic.player.subsonic.ServerSettings
import org.dancesonic.player.subsonic.SubsonicHttp
import retrofit2.HttpException
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query
import java.io.IOException

/**
 * Sign-in endpoints of a folksonic/tinysonic server. These are not part of the Subsonic API.
 * The requests carry no API key: they are how a key is obtained.
 */
internal interface AccountService {
    @POST("api/login")
    suspend fun login(
        @Body body: LoginRequest,
    ): LoginResponse

    @POST("api/keys")
    suspend fun createApiKey(
        @Header("Authorization") bearer: String,
        @Body body: CreateKeyRequest,
    ): CreateKeyResponse

    @POST("api/logout")
    suspend fun logout(
        @Header("Authorization") bearer: String,
    ): Response<Unit>

    /** Sends the token and the app's public key. The pairing then waits for approval: 202, and no key. */
    @POST("api/pair/complete")
    suspend fun completeDevicePairing(
        @Body body: DevicePairRequest,
    ): Response<PairingPending>

    /** The device this proof belongs to. Refused with `pairing_pending` until the owner approves it. */
    @GET("api/device/me")
    suspend fun deviceMe(): Response<DeviceMe>

    /** Keys can be listed and revoked with an API key, so the app needs no session for this. */
    @GET("api/keys")
    suspend fun listKeys(
        @Query("apiKey") apiKey: String,
    ): ListKeysResponse

    @DELETE("api/keys/{id}")
    suspend fun revokeKey(
        @Path("id") id: Long,
        @Query("apiKey") apiKey: String,
    ): Response<Unit>
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
internal data class DevicePairRequest(
    val token: String,
    val device: String,
    val jwk: PublicJwk,
) {
    override fun toString(): String = "DevicePairRequest(device=$device, token=<redacted>)"
}

/** The pairing was accepted and waits for approval. [status] is `pending`. */
@Serializable
internal data class PairingPending(
    val status: String = "",
)

@Serializable
internal data class DeviceMe(
    val id: Long = 0,
    val name: String = "",
)

/** The body of a refusal: `{"error": "<code>", "message": "..."}`. */
@Serializable
internal data class RefusalBody(
    val error: String? = null,
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
    suspend fun login(
        server: HttpUrl,
        username: String,
        password: String,
    ): String =
        call(unauthorized = "Wrong username or password.") {
            service(server).login(LoginRequest(username, password)).token
        }

    /** Creates a non-expiring API key for this app using a session token. */
    suspend fun createApiKey(
        server: HttpUrl,
        sessionToken: String,
    ): String =
        call(unauthorized = "Your sign-in expired. Please try again.") {
            service(server).createApiKey("Bearer $sessionToken", CreateKeyRequest(DEVICE_LABEL, expiresDays = 0)).apiKey
        }

    /**
     * Ends the session on the server. Callers use this after the API key is obtained, so a failure
     * here does not affect the connection. The session also expires on its own.
     */
    suspend fun logout(
        server: HttpUrl,
        sessionToken: String,
    ) {
        call(unauthorized = "Your sign-in already expired.") {
            val response = service(server).logout("Bearer $sessionToken")
            if (!response.isSuccessful) throw HttpException(response)
        }
    }

    /**
     * Sends the pairing token with the device's public key (folksonic #101). No key is returned: the pairing waits for
     * the owner to approve it on the account page, see [awaitDeviceApproval]. The request carries no DPoP proof.
     */
    suspend fun startDevicePairing(
        request: PairingRequest,
        device: String,
        jwk: PublicJwk,
    ) {
        val response = service(request.server).completeDevicePairing(DevicePairRequest(request.token, device, jwk))
        when {
            response.code() == HTTP_ACCEPTED -> return
            response.code() == HTTP_UNAUTHORIZED -> throw AccountException(pairingRefusal(response), null)
            else -> throw AccountException("The server returned HTTP ${response.code()}.")
        }
    }

    /**
     * Polls the device until the owner approves its pairing. Returns when the device is approved; throws when the
     * approval is refused, the device was removed, or [timeoutMillis] runs out.
     */
    suspend fun awaitDeviceApproval(
        server: HttpUrl,
        timeoutMillis: Long = PAIRING_WAIT_MILLIS,
        pollMillis: Long = POLL_INTERVAL_MILLIS,
    ) {
        val deadline = System.nanoTime() + timeoutMillis * NANOS_PER_MILLI
        while (true) {
            val response = service(server).deviceMe()
            if (response.isSuccessful) return
            val code = refusalCode(response)
            if (code != CODE_PAIRING_PENDING) throw approvalRefusal(code, response.code())
            if (System.nanoTime() >= deadline) {
                throw AccountException("The approval did not arrive in time. Create a new code on the account page.")
            }
            delay(pollMillis)
        }
    }

    private fun approvalRefusal(
        code: String?,
        status: Int,
    ): AccountException =
        when (code) {
            CODE_DEVICE_UNKNOWN -> AccountException("The device was removed on the account page. Pair it again.")
            CODE_PAIRING_EXPIRED -> AccountException(INVALID_PAIRING_CODE)
            else -> AccountException("The server returned HTTP $status.")
        }

    private fun pairingRefusal(response: Response<*>): String =
        when (refusalCode(response)) {
            CODE_PAIRING_EXPIRED, CODE_PAIRING_INVALID -> INVALID_PAIRING_CODE
            else -> "The server refused the pairing (HTTP ${response.code()})."
        }

    /**
     * Revokes this app's key on the server. Returns false when the key is no longer listed, which
     * means it was already revoked. The key is matched by its prefix; if the prefix is ambiguous
     * nothing is revoked, so no other key is touched.
     */
    suspend fun revokeOwnKey(
        server: HttpUrl,
        apiKey: String,
    ): Boolean {
        val keys =
            call(unauthorized = "The stored key is no longer valid.") {
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

    private suspend fun <T> call(
        unauthorized: String,
        block: suspend () -> T,
    ): T =
        try {
            block()
        } catch (e: HttpException) {
            when (e.code()) {
                HTTP_UNAUTHORIZED -> throw AccountException(unauthorized, e)
                else -> throw AccountException("The server returned HTTP ${e.code()}.", e)
            }
        } catch (e: SerializationException) {
            throw AccountException("The server sent an unexpected response.", e)
        } catch (e: IOException) {
            throw AccountException("Could not reach the server.", e)
        }

    companion object {
        const val DEVICE_LABEL = "DanceSonic Android"
        private const val HTTP_UNAUTHORIZED = 401
        private const val HTTP_ACCEPTED = 202
        private const val CODE_PAIRING_PENDING = "pairing_pending"
        private const val CODE_PAIRING_EXPIRED = "pairing_expired"
        private const val CODE_PAIRING_INVALID = "pairing_invalid"
        private const val CODE_DEVICE_UNKNOWN = "device_unknown"
        private const val PAIRING_WAIT_MILLIS = 15L * 60_000L
        private const val POLL_INTERVAL_MILLIS = 3_000L
        private const val NANOS_PER_MILLI = 1_000_000L

        /** A client for the device endpoints: every request carries the proof of [settings]' device key. */
        fun forDevice(settings: ServerSettings): AccountClient = AccountClient(SubsonicHttp.okHttpClient { settings })

        /** The refusal code in a failed response's body, or null when the body has none. */
        @Suppress("SwallowedException")
        internal fun refusalCode(response: Response<*>): String? {
            val text = response.errorBody()?.string() ?: return null
            return try {
                accountJson.decodeFromString<RefusalBody>(text).error
            } catch (e: SerializationException) {
                // The body is not a refusal we know; the caller then reports the HTTP status.
                null
            }
        }

        /** The id of the key whose prefix starts [apiKey], or null if zero or several match. */
        internal fun matchOwnKeyId(
            keys: List<KeyInfo>,
            apiKey: String,
        ): Long? {
            val matches = keys.filter { it.prefix.isNotEmpty() && apiKey.startsWith(it.prefix) }
            return matches.singleOrNull()?.id
        }

        const val INVALID_PAIRING_CODE =
            "This pairing code is invalid or expired. Create a new one on the account page."

        private val accountJson =
            Json {
                ignoreUnknownKeys = true
            }
    }
}
