package org.dancesonic.player.dance

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.HeaderMap
import retrofit2.http.PUT
import retrofit2.http.Path

/**
 * A recording's shoes ratings as DanceBrainz reports them. [myShoes] is null unless a token was sent and the
 * viewer has rated the recording.
 */
data class RecordingRatings(
    val shoesMedian: Double?,
    val shoesRaters: Int,
    val myShoes: Int?,
)

/**
 * Shoes ratings on DanceBrainz. The read works without a token and shows the public median; with a token it also
 * shows the viewer's own rating. A change always needs a token, and is only ever sent for the signed-in account.
 */
class DanceBrainzRatingsClient internal constructor(
    private val service: DanceBrainzRatingsService,
) {
    constructor(baseUrl: HttpUrl, client: OkHttpClient = DanceBrainzClient.defaultHttpClient()) : this(
        DanceBrainzClient.retrofitFor(baseUrl, client).create(DanceBrainzRatingsService::class.java),
    )

    /** The ratings of the recording [mbid]. Sends [credential] when given, so the viewer's own value is included. */
    suspend fun ratings(
        mbid: String,
        credential: DanceBrainzCredential?,
    ): RecordingRatings {
        val dto = danceBrainzCall { service.ratings(mbid, credential.authHeaders()) }
        return RecordingRatings(dto.shoesMedian, dto.shoesRaters, dto.myShoes)
    }

    /** Sets the viewer's shoes rating of [mbid] to [value], which must be 1 to 5. */
    suspend fun setShoes(
        mbid: String,
        value: Int,
        credential: DanceBrainzCredential,
    ) {
        require(value in SHOES_RANGE) { "A shoes rating is from 1 to 5" }
        danceBrainzCall { service.setShoes(mbid, ShoesValueDto(value), credential.authHeaders()) }
    }

    /** Clears the viewer's shoes rating of [mbid]. */
    suspend fun clearShoes(
        mbid: String,
        credential: DanceBrainzCredential,
    ) {
        danceBrainzCall { service.clearShoes(mbid, credential.authHeaders()) }
    }

    private companion object {
        val SHOES_RANGE = 1..5
    }
}

/** Retrofit endpoints of the shoes ratings, relative to the DanceBrainz base URL. */
internal interface DanceBrainzRatingsService {
    @GET("api/v1/recordings/{mbid}/ratings")
    suspend fun ratings(
        @Path("mbid") mbid: String,
        @HeaderMap auth: Map<String, String>,
    ): RatingsDto

    @PUT("api/v1/recordings/{mbid}/shoes")
    suspend fun setShoes(
        @Path("mbid") mbid: String,
        @Body body: ShoesValueDto,
        @HeaderMap auth: Map<String, String>,
    )

    @DELETE("api/v1/recordings/{mbid}/shoes")
    suspend fun clearShoes(
        @Path("mbid") mbid: String,
        @HeaderMap auth: Map<String, String>,
    )
}

@Serializable
internal data class RatingsDto(
    @SerialName("shoes_median") val shoesMedian: Double? = null,
    @SerialName("shoes_raters") val shoesRaters: Int = 0,
    @SerialName("my_shoes") val myShoes: Int? = null,
)

@Serializable
internal data class ShoesValueDto(
    val value: Int,
)
