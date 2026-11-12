package org.dancesonic.player.dance

import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import org.dancesonic.player.subsonic.LanTls
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import java.io.IOException
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

    /**
     * Confirms that the address is a DanceBrainz server. Any failure is reported as [DanceBrainzException] with a
     * message for the user, so the caller does not need to know about HTTP or JSON errors.
     */
    suspend fun checkServer() {
        val info = answerOrThrow { service.info() }
        if (info.service != SERVICE_NAME) {
            throw DanceBrainzException("This address is not a DanceBrainz server")
        }
    }

    private suspend fun <T> answerOrThrow(block: suspend () -> T): T =
        try {
            block()
        } catch (e: IOException) {
            throw DanceBrainzException("Could not reach DanceBrainz: ${e.message ?: "no answer"}", e)
        } catch (e: HttpException) {
            throw DanceBrainzException("DanceBrainz answered with HTTP ${e.code()}", e)
        } catch (e: SerializationException) {
            throw DanceBrainzException("This address does not answer like a DanceBrainz server", e)
        }

    override suspend fun lookup(mbids: List<String>): Map<String, RecordingDance> {
        require(mbids.size <= DanceMetadataSource.MAX_BATCH_SIZE) { "A lookup takes at most 500 recordings" }
        val response = service.lookup(LookupRequestDto(mbids))
        return response.recordings.associate { recording ->
            recording.mbid to
                RecordingDance(
                    danceTypes = recording.tags.filter { it.type == DANCE_TAG_TYPE }.map { it.name },
                    bpm = recording.bpm?.takeIf { it in BPM_RANGE },
                    raters = recording.raters,
                    chapters = recording.chapters.map { it.toDomain() },
                    chaptersVersion = recording.chaptersVersion,
                )
        }
    }

    companion object {
        /** The public DanceBrainz server. */
        val DEFAULT_BASE_URL: HttpUrl = "https://dancebrainz.balfolk.social/".toHttpUrl()

        /** Name the server reports in its root answer. */
        const val SERVICE_NAME = "dancebrainz"

        /** The tag type that holds dance names. */
        const val DANCE_TAG_TYPE = "dance"

        /** DanceBrainz accepts tempos from 1 to 400 bpm. */
        private val BPM_RANGE = 1..400

        private const val CONNECT_TIMEOUT_SECONDS = 15L
        private const val READ_TIMEOUT_SECONDS = 30L

        @OptIn(ExperimentalSerializationApi::class)
        internal val json: Json =
            Json {
                ignoreUnknownKeys = true
                coerceInputValues = true
            }

        /** Only https is accepted, as for the Subsonic server. */
        private fun serviceFor(
            baseUrl: HttpUrl,
            client: OkHttpClient,
        ): DanceBrainzService = retrofitFor(baseUrl, client).create(DanceBrainzService::class.java)

        /** The JSON Retrofit for a DanceBrainz server; shared with the account endpoints. */
        internal fun retrofitFor(
            baseUrl: HttpUrl,
            client: OkHttpClient,
        ): Retrofit {
            require(baseUrl.isHttps) { "DanceBrainz must be reached over https" }
            return Retrofit.Builder()
                .baseUrl(baseUrl)
                .client(client)
                .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
                .build()
        }

        internal fun defaultHttpClient(): OkHttpClient =
            LanTls.configure(OkHttpClient.Builder())
                .followSslRedirects(false)
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
                .addInterceptor(DanceBrainzDeviceInterceptor())
                .build()
    }
}

/** Retrofit endpoint, relative to the DanceBrainz base URL. */
internal interface DanceBrainzService {
    @GET("api/v1/")
    suspend fun info(): ServiceInfoDto

    @POST("api/v1/recordings/lookup")
    suspend fun lookup(
        @Body body: LookupRequestDto,
    ): LookupResponseDto
}

@Serializable
internal data class ServiceInfoDto(
    val service: String = "",
)

/**
 * A DanceBrainz server could not be used. The message is for the user. [httpCode] is the status the server
 * answered with, or null when no answer came (the phone is offline or the server is unreachable).
 */
class DanceBrainzException(
    message: String,
    cause: Throwable? = null,
    val httpCode: Int? = null,
) : IOException(message, cause)

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
    val chapters: List<ChapterDto> = emptyList(),
    @SerialName("chapters_version") val chaptersVersion: String? = null,
)

/** One chapter mark as the server sends it. [dance] is null when no dance is set. */
@Serializable
internal data class ChapterDto(
    @SerialName("start_ms") val startMs: Long,
    @SerialName("end_ms") val endMs: Long? = null,
    val title: String? = null,
    val dance: ChapterDanceDto? = null,
) {
    fun toDomain(): DanceChapter =
        DanceChapter(
            startMs = startMs,
            endMs = endMs,
            title = title?.takeIf { it.isNotBlank() },
            danceTagId = dance?.tagId,
            danceName = dance?.name,
        )
}

/** The dance of a chapter: the tag's id and display name. The path is not used on the phone. */
@Serializable
internal data class ChapterDanceDto(
    @SerialName("tag_id") val tagId: Long,
    val name: String,
)

/** One tag on a recording. Only the type and the display name are used; other fields are ignored. */
@Serializable
internal data class TagDto(
    val type: String,
    val name: String,
)
