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

/** How the app proves who it is to the server. */
sealed interface ServerAuth {
    /** An API key, typed in or issued by a password sign-in. A secret: stored encrypted. */
    data class ApiKey(val key: String) : ServerAuth {
        init {
            require(key.isNotBlank()) { "API key must not be blank" }
        }

        override fun toString(): String = "ApiKey(<redacted>)"
    }

    /**
     * A paired device (folksonic #101): every request carries a DPoP proof signed by the Keystore key named [alias].
     * The server never sees the key, and nothing secret is stored.
     */
    data class Device(val alias: String) : ServerAuth {
        init {
            require(alias.isNotBlank()) { "Device key alias must not be blank" }
        }
    }
}

/** Connection details for one Subsonic-compatible server. The API key is a secret. */
class ServerSettings(
    val baseUrl: HttpUrl,
    val auth: ServerAuth,
    /**
     * True when the server takes the key in the `X-Api-Key` header (folksonic). The key then stays out of the URL,
     * and the access log. False for other servers, which take it as the `apiKey` query parameter. Not used for a
     * device.
     */
    val keyInHeader: Boolean = false,
    /**
     * The pin of the server's certificate, for an https server on a private network that has no CA-signed certificate.
     * See [LanTls]. Null for every other server.
     */
    val tlsPin: String? = null,
) {
    /** Connects with an API key. */
    constructor(
        baseUrl: HttpUrl,
        apiKey: String,
        keyInHeader: Boolean = false,
        tlsPin: String? = null,
    ) : this(baseUrl, ServerAuth.ApiKey(apiKey), keyInHeader, tlsPin)

    init {
        require(baseUrl.isAllowedServer()) { "Server must use https, or http on a private network address" }
        require(tlsPin == null || (baseUrl.isHttps && isPrivateNetworkHost(baseUrl.host))) {
            "A certificate pin is only used for https on a private network"
        }
        require(baseUrl.encodedPath.endsWith("/")) { "Base URL path must end with '/'" }
    }

    /** The API key, or null when the connection is a paired device. */
    val apiKey: String? get() = (auth as? ServerAuth.ApiKey)?.key

    /** The same server and auth, with the key sent in [header] or in the query. */
    fun withKeyInHeader(header: Boolean): ServerSettings = ServerSettings(baseUrl, auth, header, tlsPin)

    /** The same server and auth, with [pin] as the certificate pin. */
    fun withTlsPin(pin: String?): ServerSettings = ServerSettings(baseUrl, auth, keyInHeader, pin)

    override fun toString(): String = "ServerSettings(baseUrl=$baseUrl, auth=$auth, keyInHeader=$keyInHeader)"
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
 * URL of the cover art of [albumId], at most [size] pixels on its longer side. Like [streamUrl], it carries no API key:
 * the shared client adds it when the request is sent. The album id is the cover id on folksonic and Navidrome.
 */
fun ServerSettings.coverArtUrl(
    albumId: String,
    size: Int,
): HttpUrl =
    baseUrl.resolve("rest/getCoverArt.view")!!
        .newBuilder()
        .addQueryParameter("id", albumId)
        .addQueryParameter("size", size.toString())
        .build()

/**
 * Whether a server at this URL may be used. `https://` always may. `http://` is allowed only to a private network
 * address, so a home or LAN server works without a certificate; the key then travels unencrypted on that network.
 */
internal fun HttpUrl.isAllowedServer(): Boolean = isHttps || (scheme == "http" && isPrivateNetworkHost(host))

/**
 * Whether [host] is this device or a private network address: loopback (`localhost`, `127.x.x.x`, `::1`), RFC 1918
 * (`10.x`, `172.16`–`172.31`, `192.168.x`), IPv6 unique-local (`fc00::/7`) or link-local (`fe80::/10`). Public
 * addresses and names are not private.
 */
internal fun isPrivateNetworkHost(host: String): Boolean =
    host.equals("localhost", ignoreCase = true) ||
        (if (host.contains(':')) isPrivateIpv6(host) else isPrivateIpv4(host))

private fun isPrivateIpv6(host: String): Boolean {
    val lower = host.lowercase()
    return lower == "::1" || lower.startsWith("fc") || lower.startsWith("fd") || lower.startsWith("fe80:")
}

private fun isPrivateIpv4(host: String): Boolean {
    val octets = host.split('.').mapNotNull { it.toIntOrNull() }
    if (octets.size != IPV4_OCTETS || octets.any { it !in 0..MAX_OCTET }) return false
    val first = octets[0]
    val second = octets[1]
    return first == LOOPBACK_FIRST ||
        first == CLASS_A_PRIVATE ||
        (first == CLASS_B_PRIVATE && second in CLASS_B_PRIVATE_SECOND_MIN..CLASS_B_PRIVATE_SECOND_MAX) ||
        (first == CLASS_C_PRIVATE && second == CLASS_C_PRIVATE_SECOND)
}

private const val IPV4_OCTETS = 4
private const val MAX_OCTET = 255
private const val LOOPBACK_FIRST = 127
private const val CLASS_A_PRIVATE = 10
private const val CLASS_B_PRIVATE = 172
private const val CLASS_C_PRIVATE = 192
private const val CLASS_C_PRIVATE_SECOND = 168
private const val CLASS_B_PRIVATE_SECOND_MIN = 16
private const val CLASS_B_PRIVATE_SECOND_MAX = 31

/**
 * Parses user input into a server URL. `https://` is accepted, and `http://` only to a private network address (see
 * [isAllowedServer]). The address must be a plain base URL: user
 * information, a query and a fragment are rejected, so no credential can be stored or sent as part of it. The error
 * messages never repeat the address.
 */
object ServerUrl {
    /** Like [parse], but https only. For services that are not on a private network, such as DanceBrainz. */
    fun parseHttps(input: String): HttpUrl {
        require(input.trim().startsWith("https://", ignoreCase = true)) {
            "The DanceBrainz address must start with https://"
        }
        return parse(input)
    }

    fun parse(input: String): HttpUrl {
        val trimmed = input.trim()
        // Without a scheme the address is taken as https, so a bare host name or IP never means plain http.
        val withScheme = if (trimmed.contains("://")) trimmed else "https://$trimmed"
        val url = withScheme.toHttpUrlOrNull() ?: throw IllegalArgumentException("Invalid server address")
        require(url.isAllowedServer()) {
            "The server address must start with https://, or http:// for a private network address"
        }
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
        val builder =
            request.url.newBuilder()
                .removeAllQueryParameters("apiKey")
                .removeAllQueryParameters("c")
                .removeAllQueryParameters("v")
                .removeAllQueryParameters("f")
                .addQueryParameter("c", CLIENT_NAME)
                .addQueryParameter("v", API_VERSION)
                .addQueryParameter("f", "json")
        // A paired device has no key here: the DPoP interceptor signs the request instead.
        val keyed =
            when (val key = settings.apiKey) {
                null -> request.newBuilder().url(builder.build())
                else ->
                    if (settings.keyInHeader) {
                        request.newBuilder().url(builder.build()).header(API_KEY_HEADER, key)
                    } else {
                        request.newBuilder().url(builder.addQueryParameter("apiKey", key).build())
                    }
            }
        return chain.proceed(keyed.build())
    }

    /** True when [url] is a request to the configured server's Subsonic REST API, on the same origin. */
    private fun isSubsonicApi(
        url: HttpUrl,
        server: HttpUrl,
    ): Boolean =
        url.scheme == server.scheme &&
            url.host == server.host &&
            url.port == server.port &&
            url.encodedPath.startsWith(server.encodedPath + "rest/")

    companion object {
        const val CLIENT_NAME = "DanceSonic"
        const val API_KEY_HEADER = "X-Api-Key"
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
            .addInterceptor(DeviceProofInterceptor(settingsProvider))
            // Asks folksonic for minimal responses (RFC 7240). Other servers ignore the Prefer header.
            .addInterceptor { chain ->
                chain.proceed(chain.request().newBuilder().header("Prefer", PREFER_MINIMAL).build())
            }
            // A redirect from https to http would send the key in the clear, so it is not followed.
            .followSslRedirects(false)
            .connectTimeout(CONNECT_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS)
            .let { LanTls.configure(it) }
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
