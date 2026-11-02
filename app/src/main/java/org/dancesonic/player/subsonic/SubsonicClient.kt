package org.dancesonic.player.subsonic

import kotlinx.serialization.SerializationException
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import retrofit2.HttpException
import java.io.IOException

/**
 * High-level Subsonic operations used by the app. Every method unwraps the JSON envelope and
 * converts `status: failed` responses and transport errors into [SubsonicException].
 */
class SubsonicClient internal constructor(
    val settings: ServerSettings,
    private val service: SubsonicService,
) {
    constructor(settings: ServerSettings) : this(
        settings,
        SubsonicHttp.service(
            baseUrl = settings.baseUrl,
            client = SubsonicHttp.okHttpClient { settings },
        ),
    )

    /**
     * Verifies that the server is reachable, that the API key is accepted, and that the server
     * advertises the OpenSubsonic API key extension. Throws [SubsonicException] otherwise.
     */
    suspend fun checkConnection() {
        call { ping() }
        val extensions = call { openSubsonicExtensions() }.openSubsonicExtensions.orEmpty()
        val supportsApiKey = extensions.any { ext ->
            ext.name == API_KEY_EXTENSION && ext.versions.contains(1)
        }
        if (!supportsApiKey) {
            throw SubsonicException(
                code = null,
                message = "This server does not support API key login (OpenSubsonic $API_KEY_EXTENSION).",
            )
        }
    }

    /** All artists, flattened from the alphabetical index. */
    suspend fun artists(): List<ArtistDto> =
        call { artists() }.artists?.index.orEmpty().flatMap { it.artist }

    /** One artist with its albums (no songs). */
    suspend fun artist(id: String): ArtistDto =
        call { artist(id) }.artist ?: throw SubsonicException(null, "Artist response was empty")

    /** One album with its songs. */
    suspend fun album(id: String): AlbumDto =
        call { album(id) }.album ?: throw SubsonicException(null, "Album response was empty")

    /** See [ServerSettings.streamUrl]. */
    fun streamUrl(songId: String): HttpUrl = settings.streamUrl(songId)

    private suspend fun call(block: suspend SubsonicService.() -> SubsonicEnvelope): SubsonicResponse {
        val response = try {
            service.block().response
        } catch (e: HttpException) {
            throw SubsonicException(null, "Server returned HTTP ${e.code()}", e)
        } catch (e: SerializationException) {
            throw SubsonicException(null, "Unexpected response from server", e)
        } catch (e: IOException) {
            throw SubsonicException(null, "Could not reach server", e)
        }
        if (response.status != "ok") {
            val error = response.error
            throw SubsonicException(error?.code, error?.message ?: "Server reported an error")
        }
        return response
    }

    companion object {
        /** Extension name from the OpenSubsonic apikeyauth spec. Verify against the spec before release. */
        const val API_KEY_EXTENSION = "apiKeyAuthentication"
    }
}
