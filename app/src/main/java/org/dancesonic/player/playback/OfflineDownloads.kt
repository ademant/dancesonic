package org.dancesonic.player.playback

import android.database.sqlite.SQLiteException
import android.net.Uri
import android.util.Log
import androidx.media3.datasource.DataSource
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.dancesonic.player.data.db.DanceSonicDatabase
import org.dancesonic.player.data.db.TrackEntity
import org.dancesonic.player.subsonic.ServerSettings
import org.dancesonic.player.subsonic.streamUrl
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

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

/** Runs download passes; the worker calls it (#139). */
fun interface DownloadPasses {
    /** Runs passes until nothing new was asked for. Returns true when a pass should be tried again later. */
    suspend fun runPasses(): Boolean
}

/**
 * Downloads every track of a kept album or playlist, and drops cached files of songs the server no longer has.
 *
 * The passes run in WorkManager (#139), through [scheduler]: they survive the app's process, wait for a network the
 * settings allow (unmetered, unless mobile data is allowed), and retry with backoff. [kick] asks for a pass when the
 * kept tracks, the server or a setting change; requests made while a pass runs make it run once more, so a burst of
 * changes runs few passes. A track counts as done once the cache holds it whole ([AudioCache.holdsWhole]), so a pass
 * repeated after a restart downloads only what is missing. A file cut off mid-way resumes: the cache keeps the bytes
 * it got, and the next pass reads them from disk and asks the server only for the rest, by range request.
 *
 * The status for the config page is computed here as well, without downloading, whenever something is kicked or the
 * network changes, so "waiting for Wi-Fi" shows while the worker waits for its constraint.
 */
@Suppress("LongParameterList") // Each part is replaced in tests: the cache, the network, the scheduler, the threads.
class OfflineDownloads(
    private val database: DanceSonicDatabase,
    /** Read only when a pass has a server to download from, so the cache is not opened before it is needed. */
    private val audioCache: () -> AudioCache,
    private val upstream: DataSource.Factory,
    private val conditions: () -> DownloadConditions,
    private val network: NetworkState,
    private val scheduler: DownloadScheduler,
    private val scope: CoroutineScope,
    /** Where database and file work runs. Tests pass their own (#137). */
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : DownloadPasses {
    private val _status = MutableStateFlow(OfflineStatus())
    val status: StateFlow<OfflineStatus> = _status.asStateFlow()

    /** Set by [kick], cleared when a pass starts: a pass asked for while one runs makes it run again. */
    private val dirty = AtomicBoolean(true)

    /** Starts watching for changes. Call once, when the app container is built. */
    fun start() {
        scope.launch(io) {
            database
                .keptCollectionDao()
                .observeKeptTrackIds()
                .distinctUntilChanged()
                .collect { kick() }
        }
        // Only the status follows the network here; WorkManager starts the downloads when the network allows them.
        scope.launch(io) { network.changes().collect { refreshStatus() } }
    }

    /** Asks for a pass. The worker runs it when the network allows; several requests make one pass. */
    fun kick() {
        dirty.set(true)
        scheduler.schedule(conditions().allowMobileData)
        scope.launch(io) { refreshStatus() }
    }

    override suspend fun runPasses(): Boolean =
        withContext(io) {
            var retry = false
            while (!retry && dirty.getAndSet(false)) {
                retry = runPass()
            }
            retry
        }

    /** One pass. Returns true when it should be tried again later. */
    private suspend fun runPass(): Boolean {
        val server = conditions().server ?: return false
        val cache = audioCache()
        return try {
            val kept = database.keptCollectionDao().keptTracks()
            cache.setKept(kept.mapTo(HashSet()) { it.id })
            cache.removeUnknown(database.trackDao().allIds().toSet())
            val outcome = downloadMissing(cache, kept, server)
            publish(cache, kept, outcome.block)
            outcome.retry
        } catch (e: SQLiteException) {
            Log.d(TAG, "Download pass skipped: ${e.javaClass.simpleName}")
            true
        }
    }

    /** How a pass ended: why it stopped early, if it did, and whether to try again later. */
    private data class Outcome(
        val block: DownloadBlock? = null,
        val retry: Boolean = false,
    )

    /** Downloads the kept tracks not yet on disk. */
    private suspend fun downloadMissing(
        cache: AudioCache,
        kept: List<TrackEntity>,
        server: ServerSettings,
    ): Outcome {
        // Checked lazily, one track at a time: a download finished earlier in the pass counts as held.
        val missing =
            kept
                .asSequence()
                .mapNotNull { track -> track.sizeBytes?.let { track to it } }
                .filterNot { (track, size) -> cache.holdsWhole(track.id, size) }
        for ((track, size) in missing) {
            // The network went away or became metered: WorkManager runs the pass again when it allows it.
            network.blockReason(conditions().allowMobileData)?.let { return Outcome(block = it, retry = true) }
            try {
                cache.prefetch(track.id, Uri.parse(server.streamUrl(track.id).toString()), size, upstream)
            } catch (e: IOException) {
                // The URL is never logged; the retry starts this file again.
                Log.d(TAG, "Download stopped: ${e.javaClass.simpleName}")
                return Outcome(retry = true)
            }
            publish(cache, kept, null)
        }
        return Outcome()
    }

    /** Publishes the status without downloading. Opens the cache only when a server is stored. */
    private suspend fun refreshStatus() {
        val settings = conditions()
        if (settings.server == null) return
        try {
            val kept = database.keptCollectionDao().keptTracks()
            val cache = audioCache()
            cache.setKept(kept.mapTo(HashSet()) { it.id })
            val waiting =
                kept.any { track -> track.sizeBytes?.let { !cache.holdsWhole(track.id, it) } ?: false }
            publish(cache, kept, if (waiting) network.blockReason(settings.allowMobileData) else null)
        } catch (e: SQLiteException) {
            Log.d(TAG, "Download status skipped: ${e.javaClass.simpleName}")
        }
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

    private companion object {
        const val TAG = "OfflineDownloads"
    }
}
