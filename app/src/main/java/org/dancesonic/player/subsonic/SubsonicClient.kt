package org.dancesonic.player.subsonic

import kotlinx.coroutines.delay
import kotlinx.serialization.SerializationException
import okhttp3.HttpUrl
import retrofit2.HttpException
import java.io.IOException

/**
 * High-level Subsonic operations used by the app. Every method unwraps the JSON envelope and
 * converts `status: failed` responses and transport errors into [SubsonicException].
 */
class SubsonicClient internal constructor(
    val settings: ServerSettings,
    private val service: SubsonicService,
    private val retryBackoffMillis: Long = INITIAL_BACKOFF_MILLIS,
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
        val supportsApiKey =
            extensions.any { ext ->
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
    suspend fun artists(): List<ArtistDto> = call { artists() }.artists?.index.orEmpty().flatMap { it.artist }

    /** One artist with its albums (no songs). */
    suspend fun artist(id: String): ArtistDto =
        call { artist(id) }.artist ?: throw SubsonicException(null, "Artist response was empty")

    /**
     * Every album, fetched in pages of [ALBUM_PAGE_SIZE]. Paging stops at the first empty page
     * rather than a short one, because a server may cap the page size below what was requested.
     */
    suspend fun allAlbums(): List<AlbumDto> {
        val albums = mutableListOf<AlbumDto>()
        while (true) {
            val page =
                call {
                    albumList2(type = "alphabeticalByName", size = ALBUM_PAGE_SIZE, offset = albums.size)
                }.albumList2?.album.orEmpty()
            if (page.isEmpty()) return albums
            albums += page
        }
    }

    /** One album with its songs. */
    suspend fun album(id: String): AlbumDto =
        call { album(id) }.album ?: throw SubsonicException(null, "Album response was empty")

    /** See [ServerSettings.streamUrl]. */
    fun streamUrl(songId: String): HttpUrl = settings.streamUrl(songId)

    /**
     * Runs one request. Every Subsonic call here is a GET, so a transient failure (no connection,
     * HTTP 429 or 5xx) is retried with exponential backoff. A `status: failed` answer and other HTTP
     * errors are not retried: repeating them would not change the answer.
     */
    private suspend fun call(block: suspend SubsonicService.() -> SubsonicEnvelope): SubsonicResponse {
        var attempt = 1
        var backoffMillis = retryBackoffMillis
        while (true) {
            // Only the transport step is inside the try: SubsonicException is an IOException, so an
            // envelope error caught here would be retried and reported as "could not reach server".
            val envelope: SubsonicResponse? =
                try {
                    service.block().response
                } catch (e: HttpException) {
                    if (!e.isTransient() || attempt >= MAX_ATTEMPTS) {
                        throw SubsonicException(null, "Server returned HTTP ${e.code()}", e)
                    }
                    null
                } catch (e: SerializationException) {
                    throw SubsonicException(null, "Unexpected response from server", e)
                } catch (e: IOException) {
                    if (attempt >= MAX_ATTEMPTS) throw SubsonicException(null, "Could not reach server", e)
                    null
                }
            if (envelope != null) return unwrap(envelope)
            delay(backoffMillis)
            backoffMillis *= 2
            attempt++
        }
    }

    private fun unwrap(response: SubsonicResponse): SubsonicResponse {
        if (response.status != "ok") {
            val error = response.error
            throw SubsonicException(error?.code, error?.message ?: "Server reported an error")
        }
        return response
    }

    private fun HttpException.isTransient(): Boolean = code() == HTTP_TOO_MANY_REQUESTS || code() >= HTTP_SERVER_ERROR

    companion object {
        /** Attempts per request, including the first. */
        const val MAX_ATTEMPTS = 3

        /** Delay before the first retry; doubles after each further failure. */
        const val INITIAL_BACKOFF_MILLIS = 500L

        private const val HTTP_TOO_MANY_REQUESTS = 429
        private const val HTTP_SERVER_ERROR = 500

        /** Largest page the Subsonic spec allows for getAlbumList2. */
        const val ALBUM_PAGE_SIZE = 500

        /** Extension name from the OpenSubsonic apikeyauth spec. Verify against the spec before release. */
        const val API_KEY_EXTENSION = "apiKeyAuthentication"
    }
}
