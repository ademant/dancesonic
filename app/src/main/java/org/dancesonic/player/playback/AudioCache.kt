package org.dancesonic.player.playback

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Keeps played audio on disk, least recently used first, under [maxBytes]. Entries are keyed by song id (see
 * [org.dancesonic.player.playback.PlayerController]), never by the stream URL, which carries the API key.
 * Cleared when the connection changes, because song ids belong to one server.
 */
@Suppress("UnsafeOptInUsageError") // Media3 cache classes are marked unstable; see PlayerController.
class AudioCache(
    context: Context,
    maxBytes: Long = DEFAULT_MAX_BYTES,
) {
    private val cache: SimpleCache =
        SimpleCache(
            File(context.cacheDir, DIRECTORY),
            LeastRecentlyUsedCacheEvictor(maxBytes),
            StandaloneDatabaseProvider(context),
        )

    /**
     * Wraps [upstream] so that bytes already on disk are read from the cache. A cache error falls back to the
     * network instead of failing playback.
     */
    fun dataSourceFactory(upstream: DataSource.Factory): DataSource.Factory = cachingFactory(upstream)

    private fun cachingFactory(upstream: DataSource.Factory): CacheDataSource.Factory =
        CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(upstream)
            // Block-on-cache makes a reader wait for a download already in progress for the same bytes, instead of
            // opening a second connection to the server for them.
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR or CacheDataSource.FLAG_BLOCK_ON_CACHE)

    /** True when the whole track of [size] bytes is on disk under [key]. */
    fun holdsWhole(
        key: String,
        size: Long,
    ): Boolean = cache.isCached(key, 0, size)

    /**
     * Downloads the whole track [uri] into the cache with one request, unless it is already there. It reads the track
     * the way the player does, so playback then comes from disk and the start and end of the file are not fetched
     * twice. Cancelling the caller stops the download.
     */
    suspend fun prefetch(
        key: String,
        uri: Uri,
        size: Long,
        upstream: DataSource.Factory,
    ) = withContext(Dispatchers.IO) {
        if (holdsWhole(key, size)) return@withContext
        // Unbounded, like the player's first request: the cache learns the track's total length from the response,
        // and later unbounded reads are then served from disk.
        val spec = DataSpec.Builder().setUri(uri).setKey(key).build()
        val source = cachingFactory(upstream).createDataSource()
        try {
            source.open(spec)
            val buffer = ByteArray(PREFETCH_BUFFER_BYTES)
            while (source.read(buffer, 0, buffer.size) != C.RESULT_END_OF_INPUT) {
                ensureActive()
            }
        } finally {
            source.close()
        }
    }

    /** Removes every cached track. Runs on IO because it deletes files. */
    suspend fun clear() =
        withContext(Dispatchers.IO) {
            cache.keys.toList().forEach { cache.removeResource(it) }
        }

    companion object {
        const val DEFAULT_MAX_BYTES: Long = 2L * 1024 * 1024 * 1024
        private const val DIRECTORY = "audio"
        private const val PREFETCH_BUFFER_BYTES = 64 * 1024
    }
}
