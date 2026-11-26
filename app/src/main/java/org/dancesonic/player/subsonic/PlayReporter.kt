package org.dancesonic.player.subsonic

import kotlinx.serialization.SerializationException
import okhttp3.OkHttpClient
import retrofit2.HttpException
import retrofit2.http.GET
import retrofit2.http.Query
import java.io.IOException

/**
 * Reports counted plays to the server with the Subsonic `scrobble` call (#89). It has its own endpoint, apart from
 * [SubsonicService] and [SubsonicClient], which are at the size the linter allows. It makes one attempt per call and
 * does not retry: the caller keeps a play the server has not accepted and sends it again later.
 *
 * A retry sends the same [playedAtMs] as before, so folksonic counts a play once even when the first answer was lost.
 */
class PlayReporter(
    settings: ServerSettings,
    client: OkHttpClient,
) {
    private val service: ScrobbleService =
        SubsonicHttp.retrofit(settings.baseUrl, client).create(ScrobbleService::class.java)

    /** Reports one play that started at [playedAtMs]. Throws [SubsonicException] when the server does not accept it. */
    suspend fun scrobble(
        songId: String,
        playedAtMs: Long,
    ) {
        val envelope =
            try {
                service.scrobble(songId, playedAtMs, submission = true)
            } catch (e: HttpException) {
                throw SubsonicException(null, "Server returned HTTP ${e.code()}", e)
            } catch (e: SerializationException) {
                throw SubsonicException(null, "Unexpected response from server", e)
            } catch (e: IOException) {
                throw SubsonicException(null, "Could not reach server", e)
            }
        unwrap(envelope.response)
    }
}

/** The one Subsonic endpoint [PlayReporter] uses. */
internal interface ScrobbleService {
    @GET("rest/scrobble.view")
    suspend fun scrobble(
        @Query("id") id: String,
        @Query("time") timeMs: Long,
        @Query("submission") submission: Boolean,
    ): SubsonicEnvelope
}
