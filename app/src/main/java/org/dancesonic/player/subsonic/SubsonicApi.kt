package org.dancesonic.player.subsonic

import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
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
import java.io.IOException

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

/**
 * Parses user input into a server URL. Only `https://` is accepted. The address must be a plain base URL: user
 * information, a query and a fragment are rejected, so no credential can be stored or sent as part of it. The error
 * messages never repeat the address.
 */
object ServerUrl {
    fun parse(input: String): HttpUrl {
        val trimmed = input.trim()
        require(trimmed.startsWith("https://", ignoreCase = true)) {
            "The server address must start with https://"
        }
        val url = trimmed.toHttpUrlOrNull() ?: throw IllegalArgumentException("Invalid server address")
        require(url.encodedUsername.isEmpty() && url.encodedPassword.isEmpty()) {
            "The server address must not contain a username or password. Enter the address only."
        }
        require(url.encodedQuery == null && url.encodedFragment == null) {
            "The server address must not contain a query or fragment."
        }
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
    suspend fun artist(
        @Query("id") id: String,
    ): SubsonicEnvelope

    @GET("rest/getAlbum.view")
    suspend fun album(
        @Query("id") id: String,
    ): SubsonicEnvelope

    @GET("rest/getAlbumList2.view")
    suspend fun albumList2(
        @Query("type") type: String,
        @Query("size") size: Int,
        @Query("offset") offset: Int,
    ): SubsonicEnvelope

    /** Songs only: album and artist counts are zero, so each page is a list of songs. */
    @GET("rest/search3.view")
    suspend fun searchSongs(
        @Query("query") query: String,
        @Query("songCount") songCount: Int,
        @Query("songOffset") songOffset: Int,
        @Query("albumCount") albumCount: Int,
        @Query("artistCount") artistCount: Int,
    ): SubsonicEnvelope

    @GET("rest/getSong.view")
    suspend fun song(
        @Query("id") id: String,
    ): SubsonicEnvelope

    @GET("rest/tinysonicLibraryInfo.view")
    suspend fun libraryInfo(): SubsonicEnvelope

    /** Either [since] for the first page, or [cursor] alone for the next page of the same snapshot. */
    @GET("rest/getLibraryChanges.view")
    suspend fun libraryChanges(
        @Query("since") since: Long?,
        @Query("size") size: Int?,
        @Query("cursor") cursor: String?,
    ): SubsonicEnvelope
}

/**
 * Adds the API key and the mandatory Subsonic client parameters to requests sent to the configured Subsonic API. A
 * request gets the key only when it goes to the same scheme, host and effective port as the configured server, and
 * its path is under the server's `rest/` API. Other requests, including media on the same host, pass through
 * untouched. Any authentication parameters the caller set are replaced, never duplicated.
 *
 * Per the OpenSubsonic apikeyauth extension, `u`, `p`, `t` and `s` must not be sent with `apiKey`. The key is never
 * placed in app URLs or logs by this class; it is only added to the outgoing request.
 */
internal class ApiKeyInterceptor(
    private val settingsProvider: () -> ServerSettings?,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val settings = settingsProvider()
        if (settings == null || !isSubsonicApi(request.url, settings.baseUrl)) {
            return chain.proceed(request)
        }
        val url =
            request.url.newBuilder()
                .removeAllQueryParameters("apiKey")
                .removeAllQueryParameters("c")
                .removeAllQueryParameters("v")
                .removeAllQueryParameters("f")
                .addQueryParameter("apiKey", settings.apiKey)
                .addQueryParameter("c", CLIENT_NAME)
                .addQueryParameter("v", API_VERSION)
                .addQueryParameter("f", "json")
                .build()
        return chain.proceed(request.newBuilder().url(url).build())
    }

    /** True when [url] is a request to the configured server's Subsonic REST API, on the same origin. */
    private fun isSubsonicApi(
        url: HttpUrl,
        server: HttpUrl,
    ): Boolean =
        url.scheme == "https" &&
            url.scheme == server.scheme &&
            url.host == server.host &&
            url.port == server.port &&
            url.encodedPath.startsWith(server.encodedPath + "rest/")

    companion object {
        const val CLIENT_NAME = "DanceSonic"
        const val API_VERSION = "1.16.1"
    }
}

@OptIn(ExperimentalSerializationApi::class)
internal val subsonicJson: Json =
    Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        coerceInputValues = true
    }

/** Builds HTTP clients and Retrofit services. Callers are responsible for keeping one client per app. */
internal object SubsonicHttp {
    private const val CONNECT_TIMEOUT_SECONDS = 15L
    private const val READ_TIMEOUT_SECONDS = 30L
    private const val PREFER_MINIMAL = "tinysonic-minimal"

    fun okHttpClient(settingsProvider: () -> ServerSettings?): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor(ApiKeyInterceptor(settingsProvider))
            // Asks folksonic for minimal responses (RFC 7240). Other servers ignore the Prefer header.
            .addInterceptor { chain ->
                chain.proceed(chain.request().newBuilder().header("Prefer", PREFER_MINIMAL).build())
            }
            .connectTimeout(CONNECT_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS)
            .build()

    fun service(
        baseUrl: HttpUrl,
        client: OkHttpClient,
    ): SubsonicService =
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
