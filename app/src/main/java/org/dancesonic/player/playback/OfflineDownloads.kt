package org.dancesonic.player.playback

import android.database.sqlite.SQLiteException
import android.net.Uri
import android.util.Log
import androidx.media3.datasource.DataSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import org.dancesonic.player.data.db.DanceSonicDatabase
import org.dancesonic.player.data.db.TrackEntity
import org.dancesonic.player.subsonic.ServerSettings
import org.dancesonic.player.subsonic.streamUrl
import java.io.IOException

/** What the downloads need to run: the server to fetch from, and whether mobile data may be used. */
data class DownloadConditions(
    val server: ServerSettings?,
    val allowMobileData: Boolean,
)

/** What the downloads are doing, for the offline section of the config page. */
data class OfflineStatus(
    /** Kept tracks with a known size, which can be downloaded. */
    val keptTracks: Int = 0,
    /** Kept tracks already on disk in full. */
    val heldTracks: Int = 0,
    /** Bytes of the kept tracks on disk. */
    val heldBytes: Long = 0,
    /** Kept tracks the server gave no size for. They can be downloaded only after a full rebuild. */
    val unsizedTracks: Int = 0,
    /** Bytes used by the whole audio cache: kept songs and played ones together. */
    val cacheBytes: Long = 0,
    /** Why downloads are paused, or null when they run or nothing is left to download. */
    val blocked: DownloadBlock? = null,
)

/**
 * Downloads every track of a kept album or playlist, and drops cached files of songs the server no longer has.
 * A pass runs when the set of kept tracks changes, when the network changes, when a setting changes, and after a
 * failed download, so a burst of changes runs one pass. Downloads run only while the app process is alive: they stop
 * when the app closes and start again on the next launch.
 */
class OfflineDownloads(
    private val database: DanceSonicDatabase,
    /** Read only when a pass has a server to download from, so the cache is not opened before it is needed. */
    private val audioCache: () -> AudioCache,
    private val upstream: DataSource.Factory,
    private val conditions: () -> DownloadConditions,
    private val network: DownloadNetwork,
    private val scope: CoroutineScope,
) {
    private val kicks = Channel<Unit>(Channel.CONFLATED)
    private val _status = MutableStateFlow(OfflineStatus())
    val status: StateFlow<OfflineStatus> = _status.asStateFlow()

    /** Starts watching for changes and downloading. Call once, when the app container is built. */
    fun start() {
        scope.launch(Dispatchers.IO) {
            database.keptCollectionDao().observeKeptTrackIds().distinctUntilChanged().collect { kick() }
        }
        scope.launch(Dispatchers.IO) { network.changes().collect { kick() } }
        scope.launch(Dispatchers.IO) {
            for (ignored in kicks) runPass()
        }
    }

    /** Asks for a pass soon. Several requests before the pass starts make one pass. */
    fun kick() {
        kicks.trySend(Unit)
    }

    private suspend fun runPass() {
        val server = conditions().server ?: return
        val cache = audioCache()
        try {
            val kept = database.keptCollectionDao().keptTracks()
            cache.setKept(kept.mapTo(HashSet()) { it.id })
            cache.removeUnknown(database.trackDao().allIds().toSet())
            val blocked = downloadMissing(cache, kept, server)
            publish(cache, kept, blocked)
        } catch (e: SQLiteException) {
            Log.d(TAG, "Download pass skipped: ${e.javaClass.simpleName}")
            retryLater()
        }
    }

    /** Downloads the kept tracks not yet on disk. Returns why it stopped early, or null when all are done. */
    private suspend fun downloadMissing(
        cache: AudioCache,
        kept: List<TrackEntity>,
        server: ServerSettings,
    ): DownloadBlock? {
        // Checked lazily, one track at a time: a download finished earlier in the pass counts as held.
        val missing =
            kept.asSequence()
                .mapNotNull { track -> track.sizeBytes?.let { track to it } }
                .filterNot { (track, size) -> cache.holdsWhole(track.id, size) }
        for ((track, size) in missing) {
            network.blockReason(conditions().allowMobileData)?.let { return it }
            try {
                cache.prefetch(track.id, Uri.parse(server.streamUrl(track.id).toString()), size, upstream)
            } catch (e: IOException) {
                // The URL is never logged; the next pass starts the file again.
                Log.d(TAG, "Download stopped: ${e.javaClass.simpleName}")
                retryLater()
                return null
            }
            publish(cache, kept, null)
        }
        return null
    }

    private fun publish(
        cache: AudioCache,
        kept: List<TrackEntity>,
        blocked: DownloadBlock?,
    ) {
        val heldSizes =
            kept.mapNotNull { track -> track.sizeBytes?.takeIf { cache.holdsWhole(track.id, it) } }
        _status.value =
            OfflineStatus(
                keptTracks = kept.count { it.sizeBytes != null },
                heldTracks = heldSizes.size,
                heldBytes = heldSizes.sum(),
                unsizedTracks = kept.count { it.sizeBytes == null },
                cacheBytes = cache.cacheBytes(),
                blocked = blocked,
            )
    }

    private fun retryLater() {
        scope.launch {
            delay(RETRY_DELAY_MILLIS)
            kick()
        }
    }

    private companion object {
        const val TAG = "OfflineDownloads"
        const val RETRY_DELAY_MILLIS = 60_000L
    }
}
