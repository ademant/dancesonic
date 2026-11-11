package org.dancesonic.player.analysis

import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.serialization.Serializable
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import org.dancesonic.player.subsonic.ServerSettings
import org.dancesonic.player.subsonic.SubsonicException
import org.dancesonic.player.subsonic.subsonicJson
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Query
import java.util.concurrent.TimeUnit

/**
 * What folksonic holds for one song. [peaks] is the waveform, or null while the server has none. [retryAfterSeconds]
 * is set when the server is still working on it, and says how long to wait before asking again.
 */
data class ServerAnalysis(
    val peaks: List<Int>?,
    val retryAfterSeconds: Int?,
)

/**
 * Reads folksonic's stored analysis of a song: `GET api/analysis?id=`. The API key goes in the `X-Api-Key` header,
 * never in the URL. It is used only when the server advertises `tinysonicAnalysis`; the caller checks that.
 *
 * The client does not use the Subsonic client's interceptors. Its `Prefer` header asks for minimal responses, which
 * could drop the waveform, and the Subsonic key interceptor only covers `rest/` paths.
 */
class AnalysisClient internal constructor(
    private val service: AnalysisService,
    private val apiKey: String,
) {
    constructor(settings: ServerSettings, client: OkHttpClient = defaultClient()) : this(
        Retrofit.Builder()
            .baseUrl(settings.baseUrl)
            .client(client)
            .addConverterFactory(subsonicJson.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(AnalysisService::class.java),
        settings.apiKey,
    )

    /**
     * The stored analysis of [songId]. A song the server does not know gives no peaks and no wait. Other failures throw
     * [SubsonicException] or an IOException, and never include the API key or the URL.
     */
    suspend fun fetch(songId: String): ServerAnalysis {
        val response = service.analysis(songId, apiKey)
        if (response.code() == HTTP_NOT_FOUND) return ServerAnalysis(peaks = null, retryAfterSeconds = null)
        if (!response.isSuccessful) {
            throw SubsonicException(response.code(), "Analysis request failed with HTTP ${response.code()}")
        }
        val peaks =
            response.body()?.waveform?.peaks
                ?.takeIf { it.isNotEmpty() }
                ?.map { it.coerceIn(0, PEAK_MAX) }
        val retryAfter =
            if (response.code() == HTTP_ACCEPTED) {
                response.headers()["Retry-After"]?.toIntOrNull()?.coerceAtLeast(1) ?: DEFAULT_RETRY_SECONDS
            } else {
                null
            }
        return ServerAnalysis(peaks, retryAfter)
    }

    companion object {
        /** The extension folksonic advertises for this endpoint in `getOpenSubsonicExtensions`. */
        const val EXTENSION = "tinysonicAnalysis"

        private const val HTTP_NOT_FOUND = 404
        private const val HTTP_ACCEPTED = 202
        private const val DEFAULT_RETRY_SECONDS = 30
        private const val CONNECT_TIMEOUT_SECONDS = 15L
        private const val READ_TIMEOUT_SECONDS = 30L

        private fun defaultClient(): OkHttpClient =
            OkHttpClient.Builder()
                .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .build()
    }
}

/** Retrofit endpoint, relative to the server base URL. */
internal interface AnalysisService {
    @GET("api/analysis")
    suspend fun analysis(
        @Query("id") id: String,
        @Header("X-Api-Key") apiKey: String,
    ): Response<AnalysisDto>
}

@Serializable
internal data class AnalysisDto(
    val waveform: WaveformDto? = null,
)

@Serializable
internal data class WaveformDto(
    val bins: Int? = null,
    val peaks: List<Int> = emptyList(),
)
