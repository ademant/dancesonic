package org.dancesonic.player.playback

import android.content.Context
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import kotlinx.coroutines.Dispatchers
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
    fun dataSourceFactory(upstream: DataSource.Factory): DataSource.Factory =
        CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(upstream)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

    /** Removes every cached track. Runs on IO because it deletes files. */
    suspend fun clear() =
        withContext(Dispatchers.IO) {
            cache.keys.toList().forEach { cache.removeResource(it) }
        }

    companion object {
        const val DEFAULT_MAX_BYTES: Long = 2L * 1024 * 1024 * 1024
        private const val DIRECTORY = "audio"
    }
}
