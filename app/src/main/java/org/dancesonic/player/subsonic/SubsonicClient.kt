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
        if (!supports(API_KEY_EXTENSION)) {
            throw SubsonicException(
                code = null,
                message = "This server does not support API key login (OpenSubsonic $API_KEY_EXTENSION).",
            )
        }
    }

    @Volatile
    private var extensionsCache: List<OpenSubsonicExtensionDto>? = null

    /** The extensions the server advertises, asked once per client. */
    suspend fun advertisedExtensions(): List<OpenSubsonicExtensionDto> =
        extensionsCache ?: call { openSubsonicExtensions() }.openSubsonicExtensions.orEmpty().also {
            extensionsCache = it
        }

    /** Library totals and the last library change (folksonic `tinysonicLibraryInfo`). */
    suspend fun libraryInfo(): LibraryInfoDto =
        call { libraryInfo() }.tinysonicLibraryInfo
            ?: throw SubsonicException(null, "Library info response was empty")

    /**
     * The current change-feed point, read from a single page. Take it before a full listing: a change
     * made during the listing is then picked up by the next sync, not missed.
     */
    suspend fun changeCheckpoint(): Long = fetchChangesPage(since = null, cursor = null, size = 1).serverTime

    /**
     * Every change since [since] (epoch milliseconds), or the whole library when [since] is null.
     * Pages are read until the server reports no next cursor, so the result is one snapshot.
     */
    suspend fun libraryChanges(since: Long?): LibraryChangesDto {
        var page = fetchChangesPage(since = since, cursor = null)
        val artists = page.artists.toMutableList()
        val albums = page.albums.toMutableList()
        val songs = page.songs.toMutableList()
        val deleted = page.deleted.toMutableList()
        while (page.nextCursor != null) {
            page = fetchChangesPage(since = null, cursor = page.nextCursor)
            artists += page.artists
            albums += page.albums
            songs += page.songs
            deleted += page.deleted
        }
        return LibraryChangesDto(
            full = page.full,
            serverTime = page.serverTime,
            artists = artists,
            albums = albums,
            songs = songs,
            deleted = deleted,
        )
    }

    private suspend fun fetchChangesPage(
        since: Long?,
        cursor: String?,
        size: Int = CHANGES_PAGE_SIZE,
    ): LibraryChangesDto =
        call {
            if (cursor == null) {
                libraryChanges(since, size, null)
            } else {
                libraryChanges(null, size, cursor)
            }
        }.libraryChanges ?: throw SubsonicException(null, "Change feed response was empty")

    /** One song, including its album id. */
    suspend fun song(id: String): SongDto =
        call { song(id) }.song ?: throw SubsonicException(null, "Song response was empty")

    /** All artists, flattened from the alphabetical index. */
    suspend fun artists(): List<ArtistDto> = call { artists() }.artists?.index.orEmpty().flatMap { it.artist }

    /** One artist with its albums (no songs). */
    suspend fun artist(id: String): ArtistDto =
        call { artist(id) }.artist ?: throw SubsonicException(null, "Artist response was empty")

    /** Every album, paged with getAlbumList2. See [readAllPages] for when paging stops. */
    suspend fun allAlbums(): List<AlbumDto> =
        readAllPages(shortPageIsLast = supports(PAGING_EXTENSION), pageSize = ALBUM_PAGE_SIZE) { offset ->
            call {
                albumList2(type = "alphabeticalByName", size = ALBUM_PAGE_SIZE, offset = offset)
            }.albumList2?.album.orEmpty()
        }

    /** Every song in the library, paged with search3 and an empty query. See [readAllPages]. */
    suspend fun allSongs(): List<SongDto> =
        readAllPages(shortPageIsLast = supports(PAGING_EXTENSION), pageSize = SONG_PAGE_SIZE) { offset ->
            call {
                searchSongs(
                    query = "",
                    songCount = SONG_PAGE_SIZE,
                    songOffset = offset,
                    albumCount = 0,
                    artistCount = 0,
                )
            }.searchResult3?.song.orEmpty()
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
                    if (!isTransient(e) || attempt >= MAX_ATTEMPTS) {
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

    companion object {
        /** Attempts per request, including the first. */
        const val MAX_ATTEMPTS = 3

        /** Delay before the first retry; doubles after each further failure. */
        const val INITIAL_BACKOFF_MILLIS = 500L

        /** Songs per search3 page. Servers may cap this lower; paging stops at the first empty page. */
        const val SONG_PAGE_SIZE = 500

        /** Largest page the Subsonic spec allows for getAlbumList2. */
        const val ALBUM_PAGE_SIZE = 500

        /** Largest page the folksonic change feed accepts (`size`, 1–500). */
        const val CHANGES_PAGE_SIZE = 500

        /** folksonic paging extension: `size` is capped at 500 and a short page is the last one. */
        const val PAGING_EXTENSION = "tinysonicPaging"

        /** Extension name from the OpenSubsonic apikeyauth spec. Verify against the spec before release. */
        const val API_KEY_EXTENSION = "apiKeyAuthentication"
    }
}

/**
 * Reads pages until the listing is complete. A short page is the last one only when [shortPageIsLast]
 * is set, which is the case when the server advertises `tinysonicPaging`: it caps the page size and keeps
 * a stable order. Other servers may cap the page size below what was asked, so for them paging stops
 * only at an empty page.
 */
private suspend fun <T> readAllPages(
    shortPageIsLast: Boolean,
    pageSize: Int,
    fetchPage: suspend (offset: Int) -> List<T>,
): List<T> {
    val items = mutableListOf<T>()
    while (true) {
        val page = fetchPage(items.size)
        if (page.isEmpty()) return items
        items += page
        if (shortPageIsLast && page.size < pageSize) return items
    }
}

/** Throws for a `status: failed` envelope; returns the response otherwise. */
private fun unwrap(response: SubsonicResponse): SubsonicResponse {
    if (response.status != "ok") {
        val error = response.error
        throw SubsonicException(error?.code, error?.message ?: "Server reported an error")
    }
    return response
}

/** Whether the server advertises extension [name] at version 1. Asked once per client. */
suspend fun SubsonicClient.supports(name: String): Boolean =
    advertisedExtensions().any { it.name == name && it.versions.contains(1) }

/**
 * Whether the server is folksonic or tinysonic, which take the API key in the `X-Api-Key` header. Both advertise their
 * own `tinysonic…` extensions; other servers do not.
 */
suspend fun SubsonicClient.advertisesTinysonic(): Boolean =
    advertisedExtensions().any {
        it.name.startsWith("tinysonic")
    }

/** HTTP 429 and 5xx are worth retrying; other HTTP errors are not. */
private fun isTransient(e: HttpException): Boolean = e.code() == HTTP_TOO_MANY_REQUESTS || e.code() >= HTTP_SERVER_ERROR

private const val HTTP_TOO_MANY_REQUESTS = 429
private const val HTTP_SERVER_ERROR = 500
