package org.dancesonic.player.subsonic

import java.io.IOException
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Response
import retrofit2.Retrofit
import retrofit2.http.GET
import retrofit2.http.Query
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory

/** Connection details for one Subsonic-compatible server. The API key is a secret. */
class ServerSettings(
    val baseUrl: HttpUrl,
    val apiKey: String,
) {
    init {
        require(baseUrl.scheme == "https") { "Server must use https" }
        require(baseUrl.encodedPath.endsWith("/")) { "Base URL path must end with '/'" }
        require(apiKey.isNotBlank()) { "API key must not be blank" }
    }

    override fun toString(): String = "ServerSettings(baseUrl=$baseUrl, apiKey=<redacted>)"
}

/**
 * URL for streaming a song. The API key is deliberately absent: the shared HTTP client's
 * [ApiKeyInterceptor] adds it when the request is sent, so the URL itself is not a secret.
 */
fun ServerSettings.streamUrl(songId: String): HttpUrl =
    baseUrl.resolve("rest/stream.view")!!
        .newBuilder()
        .addQueryParameter("id", songId)
        .build()

/** Parses user input into a server URL. Only `https://` is accepted. */
object ServerUrl {
    fun parse(input: String): HttpUrl {
        val trimmed = input.trim()
        require(trimmed.startsWith("https://", ignoreCase = true)) {
            "The server address must start with https://"
        }
        val url = trimmed.toHttpUrlOrNull() ?: throw IllegalArgumentException("Invalid server address")
        return if (url.encodedPath.endsWith("/")) url else url.newBuilder().addPathSegment("").build()
    }
}

/** Retrofit endpoints. Paths are relative to the server base URL. */
internal interface SubsonicService {
    @GET("rest/ping.view")
    suspend fun ping(): SubsonicEnvelope

    @GET("rest/getOpenSubsonicExtensions.view")
    suspend fun openSubsonicExtensions(): SubsonicEnvelope

    @GET("rest/getArtists.view")
    suspend fun artists(): SubsonicEnvelope

    @GET("rest/getArtist.view")
    suspend fun artist(@Query("id") id: String): SubsonicEnvelope

    @GET("rest/getAlbum.view")
    suspend fun album(@Query("id") id: String): SubsonicEnvelope

    @GET("rest/getAlbumList2.view")
    suspend fun albumList2(
        @Query("type") type: String,
        @Query("size") size: Int,
        @Query("offset") offset: Int,
    ): SubsonicEnvelope
}

/**
 * Adds the API key and the mandatory Subsonic client parameters to every request sent to the
 * configured server. The key is never placed in app URLs or logs by this class; it is only added
 * to the outgoing request. Requests to other hosts pass through untouched.
 *
 * Per the OpenSubsonic apikeyauth extension, `u`, `p`, `t` and `s` must not be sent with `apiKey`.
 */
internal class ApiKeyInterceptor(
    private val settingsProvider: () -> ServerSettings?,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val settings = settingsProvider()
        if (settings == null || request.url.host != settings.baseUrl.host || request.url.scheme != "https") {
            return chain.proceed(request)
        }
        val url = request.url.newBuilder()
            .addQueryParameter("apiKey", settings.apiKey)
            .addQueryParameter("c", CLIENT_NAME)
            .addQueryParameter("v", API_VERSION)
            .addQueryParameter("f", "json")
            .build()
        return chain.proceed(request.newBuilder().url(url).build())
    }

    companion object {
        const val CLIENT_NAME = "DanceSonic"
        const val API_VERSION = "1.16.1"
    }
}

@OptIn(ExperimentalSerializationApi::class)
internal val subsonicJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    coerceInputValues = true
}

/** Builds HTTP clients and Retrofit services. Callers are responsible for keeping one client per app. */
internal object SubsonicHttp {
    fun okHttpClient(settingsProvider: () -> ServerSettings?): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor(ApiKeyInterceptor(settingsProvider))
            .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .build()

    fun service(baseUrl: HttpUrl, client: OkHttpClient): SubsonicService =
        Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(client)
            .addConverterFactory(subsonicJson.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(SubsonicService::class.java)
}

/** Raised when the server answers with `status: failed` or the request could not be completed. */
class SubsonicException(
    val code: Int?,
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)
