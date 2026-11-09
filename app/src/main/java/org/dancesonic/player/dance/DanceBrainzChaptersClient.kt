package org.dancesonic.player.dance

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path

/** The chapters of one recording as the server holds them now. [version] is what a write sends as `If-Match`. */
data class ChapterList(
    val version: String?,
    val lengthMs: Long?,
    val chapters: List<DanceChapter>,
)

/**
 * A change to one chapter. A field left at its default stays as it is. [clearTitle] and [clearDance] remove the
 * title or the dance; [newStartMs] moves the chapter.
 */
data class ChapterChange(
    val newStartMs: Long? = null,
    val title: String? = null,
    val clearTitle: Boolean = false,
    val danceTagId: Long? = null,
    val clearDance: Boolean = false,
)

/**
 * Reads and writes the chapter marks of a recording. Reading needs no token. A write needs a token and sends the
 * version the caller last read as `If-Match`; the server refuses a write made on an older version with 412, and
 * nothing is stored. A write answers `pending` when a moderated server holds it for review.
 */
class DanceBrainzChaptersClient internal constructor(
    private val service: DanceBrainzChaptersService,
) {
    constructor(baseUrl: HttpUrl, client: OkHttpClient = DanceBrainzClient.defaultHttpClient()) : this(
        DanceBrainzClient.retrofitFor(baseUrl, client).create(DanceBrainzChaptersService::class.java),
    )

    /** The chapters of [mbid], with their version and the recording's length. */
    suspend fun read(mbid: String): ChapterList =
        danceBrainzCall {
            val answer = service.read(mbid)
            ChapterList(
                version = answer.version,
                lengthMs = answer.lengthMs,
                chapters = answer.chapters.map { it.toDomain() },
            )
        }

    /** Marks a chapter at [startMs], with no title or dance: a position can be marked before it is named. */
    suspend fun add(
        mbid: String,
        startMs: Long,
        version: String?,
        token: String,
    ): TagWriteResult {
        val body = buildJsonObject { put("start_ms", startMs) }
        val outcome = danceBrainzCall { service.add(mbid, body, version?.let(::quoted), bearer(token)) }
        return TagWriteResult(pending = outcome.change.status == PENDING)
    }

    /** Changes the chapter that starts at [startMs]. Only the fields set in [change] are sent. */
    suspend fun update(
        mbid: String,
        startMs: Long,
        change: ChapterChange,
        version: String?,
        token: String,
    ): TagWriteResult {
        val outcome =
            danceBrainzCall {
                service.update(mbid, startMs, change.toJson(), version?.let(::quoted), bearer(token))
            }
        return TagWriteResult(pending = outcome.change.status == PENDING)
    }

    /** Removes the chapter that starts at [startMs]. */
    suspend fun delete(
        mbid: String,
        startMs: Long,
        version: String?,
        token: String,
    ): TagWriteResult {
        val outcome = danceBrainzCall { service.delete(mbid, startMs, version?.let(::quoted), bearer(token)) }
        return TagWriteResult(pending = outcome.change.status == PENDING)
    }

    private fun bearer(token: String): String = "Bearer $token"

    private fun quoted(version: String): String = "\"$version\""

    companion object {
        private const val PENDING = "pending"
    }
}

/**
 * The PATCH body. A field left out stays; `null` clears it. Built by hand because a data class would send every
 * field, and a field left out must not be sent as null.
 */
private fun ChapterChange.toJson(): JsonObject =
    buildJsonObject {
        newStartMs?.let { put("start_ms", it) }
        when {
            clearTitle -> put("title", JsonNull)
            title != null -> put("title", title)
        }
        when {
            clearDance -> put("tag_id", JsonNull)
            danceTagId != null -> put("tag_id", danceTagId)
        }
    }

/** Retrofit endpoints of the chapter marks, relative to the DanceBrainz base URL. */
internal interface DanceBrainzChaptersService {
    @GET("api/v1/recordings/{mbid}/chapters")
    suspend fun read(
        @Path("mbid") mbid: String,
    ): ChapterListDto

    @POST("api/v1/recordings/{mbid}/chapters")
    suspend fun add(
        @Path("mbid") mbid: String,
        @Body body: JsonObject,
        @Header("If-Match") ifMatch: String?,
        @Header("Authorization") bearer: String,
    ): TagChangeDto

    @PATCH("api/v1/recordings/{mbid}/chapters/{startMs}")
    suspend fun update(
        @Path("mbid") mbid: String,
        @Path("startMs") startMs: Long,
        @Body body: JsonObject,
        @Header("If-Match") ifMatch: String?,
        @Header("Authorization") bearer: String,
    ): TagChangeDto

    @DELETE("api/v1/recordings/{mbid}/chapters/{startMs}")
    suspend fun delete(
        @Path("mbid") mbid: String,
        @Path("startMs") startMs: Long,
        @Header("If-Match") ifMatch: String?,
        @Header("Authorization") bearer: String,
    ): TagChangeDto
}

@Serializable
internal data class ChapterListDto(
    @SerialName("length_ms") val lengthMs: Long? = null,
    val version: String? = null,
    val chapters: List<ChapterDto> = emptyList(),
)
