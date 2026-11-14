package org.dancesonic.player.dance

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.HeaderMap
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * A dance value that matches a typed name. [path] is what a tag write takes, such as
 * `gavotte/gavotte des montagnes`.
 */
data class DanceSuggestion(
    val path: String,
    val name: String,
    /** The tag's id, which a chapter's dance takes. Null when the suggestion did not carry one. */
    val tagId: Long? = null,
)

/**
 * A dance tag a recording currently has. [tagId] is what a replace takes; [qualifier] is `learn`, `disco` or
 * `both`.
 */
data class CurrentDance(
    val tagId: Long,
    val name: String,
    val qualifier: String,
)

/**
 * A meter value of DanceBrainz. A top-level value is a meter such as `8/4`; a value with a [parentId] is a sub-meter
 * such as `3+3+2` under its meter.
 */
data class MeterValue(
    val tagId: Long,
    val name: String,
    val parentId: Long?,
)

/** The answer to a tag write: [pending] is true when the server held the change for review (a moderated server). */
data class TagWriteResult(
    val pending: Boolean,
)

/**
 * Dance tags on DanceBrainz: suggestions while typing, the tags a recording has, and the two writes. A write needs
 * a token; reading does not. The server has no version check on a write, so a caller that replaces tags must read
 * them first (see [DanceBrainzEdits]).
 */
class DanceBrainzTagsClient internal constructor(
    private val service: DanceBrainzTagsService,
) {
    constructor(baseUrl: HttpUrl, client: OkHttpClient = DanceBrainzClient.defaultHttpClient()) : this(
        DanceBrainzClient.retrofitFor(baseUrl, client).create(DanceBrainzTagsService::class.java),
    )

    /** Dance values close to [name], best first. Empty when nothing is close. */
    suspend fun suggest(name: String): List<DanceSuggestion> =
        danceBrainzCall {
            decodeSuggestions(service.suggest(name))
                .mapNotNull { row ->
                    row.path?.let { DanceSuggestion(path = it, name = row.tag.name, tagId = row.tag.id) }
                }
        }

    /** The dance tags of [mbid], with their qualifiers. Sends [credential] when given. */
    suspend fun currentDances(
        mbid: String,
        credential: DanceBrainzCredential?,
    ): List<CurrentDance> =
        danceBrainzCall { service.tags(mbid, credential.authHeaders()) }
            .tags
            .filter { it.tag.type == DANCE_TYPE }
            .map { CurrentDance(tagId = it.tag.id, name = it.tag.name, qualifier = it.qualifier.orEmpty()) }

    /** Adds the dance at [path] with [qualifier] to [mbid]. */
    suspend fun addDance(
        mbid: String,
        path: String,
        qualifier: String,
        credential: DanceBrainzCredential,
    ): TagWriteResult {
        val body = AddTagsBody(listOf(AddTagDto(type = DANCE_TYPE, path = path, qualifier = qualifier)))
        val outcome = danceBrainzCall { service.add(mbid, body, credential.authHeaders()) }
        return TagWriteResult(pending = outcome.change.status == PENDING)
    }

    /** Every meter and sub-meter value of DanceBrainz. The list is public, so no token is needed. */
    suspend fun meterValues(): List<MeterValue> =
        danceBrainzCall { service.meterValues() }.map { MeterValue(it.id, it.name, it.parentId) }

    /**
     * Sets the meter of [mbid] to the value with [tagId], a meter or a sub-meter. The server replaces the recording's
     * meter with it and derives the meter from a sub-meter's parent.
     */
    suspend fun replaceMeter(
        mbid: String,
        tagId: Long,
        credential: DanceBrainzCredential,
    ): TagWriteResult {
        val body = ReplaceTagsBody(listOf(ReplaceTagDto(tagId = tagId)))
        val outcome = danceBrainzCall { service.replace(mbid, METER_TYPE, body, credential.authHeaders()) }
        return TagWriteResult(pending = outcome.change.status == PENDING)
    }

    /** Replaces all dance tags of [mbid] with [dances]. Tags of other types are left alone. */
    suspend fun replaceDances(
        mbid: String,
        dances: List<CurrentDance>,
        credential: DanceBrainzCredential,
    ): TagWriteResult {
        val body = ReplaceTagsBody(dances.map { ReplaceTagDto(tagId = it.tagId, qualifier = it.qualifier) })
        val outcome = danceBrainzCall { service.replace(mbid, DANCE_TYPE, body, credential.authHeaders()) }
        return TagWriteResult(pending = outcome.change.status == PENDING)
    }

    companion object {
        /** The tag type that holds dance names; shared with [DanceBrainzClient.DANCE_TAG_TYPE]. */
        private const val DANCE_TYPE = DanceBrainzClient.DANCE_TAG_TYPE
        private const val METER_TYPE = DanceBrainzClient.METER_TAG_TYPE
        private const val PENDING = "pending"
    }
}

/**
 * The suggestions in a suggest answer. The server answers `null`, not an empty list, when nothing is close, so the
 * answer is read as a raw JSON element and only an array is decoded.
 */
private fun decodeSuggestions(answer: JsonElement): List<SuggestionDto> =
    if (answer is JsonArray) {
        DanceBrainzClient.json.decodeFromJsonElement(ListSerializer(SuggestionDto.serializer()), answer)
    } else {
        emptyList()
    }

/** Retrofit endpoints of the dance tags, relative to the DanceBrainz base URL. */
internal interface DanceBrainzTagsService {
    @GET("api/v1/tag-types/dance/suggest")
    suspend fun suggest(
        @Query("name") name: String,
    ): JsonElement

    @GET("api/v1/recordings/{mbid}/tags")
    suspend fun tags(
        @Path("mbid") mbid: String,
        @HeaderMap auth: Map<String, String>,
    ): TrackTagsDto

    @POST("api/v1/recordings/{mbid}/tags")
    suspend fun add(
        @Path("mbid") mbid: String,
        @Body body: AddTagsBody,
        @HeaderMap auth: Map<String, String>,
    ): TagChangeDto

    @GET("api/v1/tag-types/meter/tags")
    suspend fun meterValues(): List<MeterValueDto>

    @PUT("api/v1/recordings/{mbid}/tags")
    suspend fun replace(
        @Path("mbid") mbid: String,
        @Query("type") type: String,
        @Body body: ReplaceTagsBody,
        @HeaderMap auth: Map<String, String>,
    ): TagChangeDto
}

@Serializable
internal data class SuggestionDto(
    val tag: TagRefDto,
    val path: String? = null,
)

@Serializable
internal data class TrackTagsDto(
    val tags: List<TrackTagDto> = emptyList(),
)

@Serializable
internal data class TrackTagDto(
    val tag: TagRefDto,
    val qualifier: String? = null,
)

/** The parts of a tag the app uses. Other fields, such as the parent or aliases, are ignored. */
@Serializable
internal data class TagRefDto(
    val id: Long = 0,
    val type: String = "",
    val name: String = "",
)

@Serializable
internal data class TagChangeDto(
    val change: ChangeStatusDto = ChangeStatusDto(),
)

@Serializable
internal data class ChangeStatusDto(
    val status: String = "",
)

@Serializable
internal data class AddTagsBody(
    val tags: List<AddTagDto>,
)

@Serializable
internal data class AddTagDto(
    val type: String,
    val path: String,
    val qualifier: String,
)

@Serializable
internal data class ReplaceTagsBody(
    val tags: List<ReplaceTagDto>,
)

/**
 * One tag of a replace. A qualifier applies to dances only, so it is left out (null) for a meter, and the default
 * null is not encoded.
 */
@Serializable
internal data class ReplaceTagDto(
    @SerialName("tag_id") val tagId: Long,
    val qualifier: String? = null,
)

@Serializable
internal data class MeterValueDto(
    val id: Long = 0,
    val name: String = "",
    @SerialName("parent_id") val parentId: Long? = null,
)
