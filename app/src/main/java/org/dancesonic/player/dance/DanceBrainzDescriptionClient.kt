package org.dancesonic.player.dance

import kotlinx.serialization.Serializable
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import retrofit2.http.GET
import retrofit2.http.Path

/**
 * Reads whether DanceBrainz stores a dance description for a recording. Reading is public and needs no token. The text
 * itself is shown on the DanceBrainz recording page, which the app links to.
 */
class DanceBrainzDescriptionClient internal constructor(
    private val service: DanceBrainzDescriptionService,
) {
    constructor(baseUrl: HttpUrl, client: OkHttpClient = DanceBrainzClient.defaultHttpClient()) : this(
        DanceBrainzClient.retrofitFor(baseUrl, client).create(DanceBrainzDescriptionService::class.java),
    )

    /**
     * True when a description is stored for [mbid], false when none is (the server answers 404). Other failures throw
     * [DanceBrainzException], so the caller can keep the last answer.
     */
    suspend fun hasDescription(mbid: String): Boolean =
        try {
            danceBrainzCall { service.description(mbid) }
            true
        } catch (e: DanceBrainzException) {
            if (e.httpCode == NOT_FOUND) false else throw e
        }

    /** The description text of [mbid], or null when none is stored. Other failures throw [DanceBrainzException]. */
    suspend fun descriptionText(mbid: String): String? =
        try {
            danceBrainzCall { service.description(mbid) }.text
        } catch (e: DanceBrainzException) {
            if (e.httpCode == NOT_FOUND) null else throw e
        }

    private companion object {
        const val NOT_FOUND = 404
    }
}

/** Retrofit endpoint for the dance description, relative to the DanceBrainz base URL. */
internal interface DanceBrainzDescriptionService {
    @GET("api/v1/recordings/{mbid}/description")
    suspend fun description(
        @Path("mbid") mbid: String,
    ): DescriptionDto
}

/** The stored description. Only its presence is used; the text is read on the web page. */
@Serializable
internal data class DescriptionDto(
    val recording: String? = null,
    val text: String? = null,
)
