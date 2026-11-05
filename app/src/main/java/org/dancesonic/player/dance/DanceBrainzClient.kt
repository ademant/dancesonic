package org.dancesonic.player.dance

import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.http.Body
import retrofit2.http.POST
import java.util.concurrent.TimeUnit

/**
 * Client for the DanceBrainz batch lookup (`POST /api/v1/recordings/lookup`). Lookups are anonymous, so
 * this client never sends an API key and must not share the Subsonic HTTP client, which adds one.
 */
class DanceBrainzClient internal constructor(
    private val service: DanceBrainzService,
) : DanceMetadataSource {
    constructor(
        baseUrl: HttpUrl = DEFAULT_BASE_URL,
        client: OkHttpClient = defaultHttpClient(),
    ) : this(serviceFor(baseUrl, client))

    override suspend fun lookup(mbids: List<String>): Map<String, RecordingDance> {
        require(mbids.size <= DanceMetadataSource.MAX_BATCH_SIZE) { "A lookup takes at most 500 recordings" }
        val response = service.lookup(LookupRequestDto(mbids))
        return response.recordings.associate { recording ->
            recording.mbid to
                RecordingDance(
                    danceTypes = recording.tags.filter { it.type == DANCE_TAG_TYPE }.map { it.name },
                    bpm = recording.bpm?.takeIf { it in BPM_RANGE },
                    raters = recording.raters,
                )
        }
    }

    companion object {
        /** The public DanceBrainz server. */
        val DEFAULT_BASE_URL: HttpUrl = "https://dancebrainz.balfolk.social/".toHttpUrl()

        /** The tag type that holds dance names. */
        const val DANCE_TAG_TYPE = "dance"

        /** DanceBrainz accepts tempos from 1 to 400 bpm. */
        private val BPM_RANGE = 1..400

        private const val CONNECT_TIMEOUT_SECONDS = 15L
        private const val READ_TIMEOUT_SECONDS = 30L

        @OptIn(ExperimentalSerializationApi::class)
        private val json: Json =
            Json {
                ignoreUnknownKeys = true
                coerceInputValues = true
            }

        /** Only https is accepted, as for the Subsonic server. */
        private fun serviceFor(
            baseUrl: HttpUrl,
            client: OkHttpClient,
        ): DanceBrainzService {
            require(baseUrl.isHttps) { "DanceBrainz must be reached over https" }
            return Retrofit.Builder()
                .baseUrl(baseUrl)
                .client(client)
                .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
                .build()
                .create(DanceBrainzService::class.java)
        }

        private fun defaultHttpClient(): OkHttpClient =
            OkHttpClient.Builder()
                .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .addInterceptor { chain ->
                    chain.proceed(
                        chain.request().newBuilder()
                            .header("Accept", "application/json")
                            .header("User-Agent", "DanceSonic")
                            .build(),
                    )
                }
                .build()
    }
}

/** Retrofit endpoint, relative to the DanceBrainz base URL. */
internal interface DanceBrainzService {
    @POST("api/v1/recordings/lookup")
    suspend fun lookup(
        @Body body: LookupRequestDto,
    ): LookupResponseDto
}

@Serializable
internal data class LookupRequestDto(
    val mbids: List<String>,
)

@Serializable
internal data class LookupResponseDto(
    val recordings: List<RecordingDto> = emptyList(),
)

@Serializable
internal data class RecordingDto(
    val mbid: String,
    val tags: List<TagDto> = emptyList(),
    val bpm: Int? = null,
    val raters: Int? = null,
)

/** One tag on a recording. Only the type and the display name are used; other fields are ignored. */
@Serializable
internal data class TagDto(
    val type: String,
    val name: String,
)
