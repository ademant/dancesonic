package org.dancesonic.player.playback

import android.net.Uri
import android.util.Log
import androidx.media3.datasource.DataSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import okhttp3.HttpUrl
import java.io.IOException
import java.util.concurrent.CancellationException

/**
 * Downloads a track into [cache] as soon as it starts, so playback reads it from disk. Playback then makes one
 * request for the file instead of reopening it, which is what downloaded the start of a song twice. Only one
 * download runs at a time: a newly started track replaces the one still running.
 */
class AudioPrefetcher(
    private val cache: AudioCache,
    private val upstream: DataSource.Factory,
    private val scope: CoroutineScope,
) {
    private var job: Job? = null
    private var activeSongId: String? = null

    /**
     * Starts downloading [url] for [songId] when its [size] is known and at most [MAX_BYTES]. A track already being
     * downloaded is left alone, so the one that starts a queue is not restarted when it begins playing.
     */
    fun prefetch(
        songId: String,
        url: HttpUrl,
        size: Long,
    ) {
        if (size <= 0 || size > MAX_BYTES) return
        if (songId == activeSongId && job?.isActive == true) return
        job?.cancel()
        activeSongId = songId
        job =
            scope.launch(Dispatchers.IO) {
                try {
                    cache.prefetch(songId, Uri.parse(url.toString()), size, upstream)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: IOException) {
                    // The player fetches the track itself; the URL is never logged.
                    Log.d(TAG, "Prefetch stopped: ${e.javaClass.simpleName}")
                }
            }
    }

    private companion object {
        /** Larger files are left to the player, so a skipped track does not cost a long download. */
        const val MAX_BYTES = 32L * 1024 * 1024
        const val TAG = "AudioPrefetcher"
    }
}
