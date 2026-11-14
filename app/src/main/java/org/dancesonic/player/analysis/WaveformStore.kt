package org.dancesonic.player.analysis

import android.net.Uri
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import org.dancesonic.player.data.db.OnsetsEntity
import org.dancesonic.player.data.db.TrackDao
import org.dancesonic.player.data.db.WaveformDao
import org.dancesonic.player.data.db.WaveformEntity
import org.dancesonic.player.playback.AudioCache
import org.dancesonic.player.subsonic.ServerSettings
import org.dancesonic.player.subsonic.SubsonicClient
import org.dancesonic.player.subsonic.SubsonicException
import org.dancesonic.player.subsonic.streamUrl
import org.dancesonic.player.subsonic.supports
import java.io.IOException

/**
 * The waveforms of songs, for the seek bar. The server's analysis comes first: folksonic stores it, and a song it is
 * still working on is asked again after its Retry-After. Only when the server has none, and the track is already whole
 * in the audio cache, is the waveform decoded on the device. A track is never streamed just to analyse it.
 *
 * A server waveform replaces a local one; a local one is never replaced.
 *
 * The same answer carries the song's onsets, where folksonic serves them; they are stored next to the waveform for
 * the time-stretch. A server that had none is asked again after [ONSETS_RECHECK_MS], in case it was upgraded.
 */
class WaveformStore(
    private val dao: WaveformDao,
    private val tracks: TrackDao,
    private val audio: AudioCache,
    private val settingsProvider: () -> ServerSettings?,
    private val analysisFor: (ServerSettings) -> AnalysisClient = { AnalysisClient(it) },
    private val serverSupportsAnalysis: suspend (ServerSettings) -> Boolean = {
        SubsonicClient(it).supports(AnalysisClient.EXTENSION)
    },
) {
    /** The peaks of [songId], or null while there are none. Emits again when they are stored. */
    fun observe(songId: String): Flow<List<Int>?> =
        dao.observe(songId).map { row -> row?.let { decodePeaks(it.peaks) } }

    /** The stored onsets of [songId], or null when there are none. */
    suspend fun onsets(songId: String): Onsets? {
        val row = dao.getOnsets(songId) ?: return null
        return decodeOnsets(row.timesMs, row.strengths)?.takeIf { it.size > 0 }
    }

    /**
     * Asks folksonic for the stored waveform and onsets of [songId], waiting out its 202 answers a few times. Does
     * nothing when the server does not advertise the analysis endpoint, or when both are already stored.
     */
    suspend fun loadFromServer(songId: String) {
        val settings = settingsProvider() ?: return
        if (dao.get(songId)?.source == SOURCE_SERVER && hasOnsetsAnswer(songId)) return
        try {
            fetchServerWaveform(settings, songId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: SubsonicException) {
            // The message is fixed text with the HTTP status; it never carries the key or the URL.
            Log.d(TAG, "Server waveform skipped: ${e.message}")
        } catch (e: IOException) {
            Log.d(TAG, "Server waveform skipped: ${e.javaClass.simpleName}")
        } catch (e: SerializationException) {
            Log.d(TAG, "Server waveform skipped: ${e.javaClass.simpleName}")
        }
    }

    private suspend fun fetchServerWaveform(
        settings: ServerSettings,
        songId: String,
    ) {
        if (!serverSupportsAnalysis(settings)) return
        val client = analysisFor(settings)
        repeat(MAX_SERVER_ATTEMPTS) { attempt ->
            val answer = client.fetch(songId)
            val peaks = answer.peaks
            if (peaks != null) {
                dao.upsert(WaveformEntity(songId, encodePeaks(peaks), SOURCE_SERVER, System.currentTimeMillis()))
                // A finished analysis without onsets is stored as none, so the song is not asked again at once.
                saveOnsets(songId, answer.onsets ?: Onsets.NONE)
                return
            }
            answer.onsets?.let { saveOnsets(songId, it) }
            // No retry hint means the server has nothing for the song and will not compute it on request.
            val wait = answer.retryAfterSeconds ?: return
            if (attempt < MAX_SERVER_ATTEMPTS - 1) delay(wait * MILLIS_PER_SECOND)
        }
    }

    /** True when onsets are stored, or the server said recently that it has none. */
    private suspend fun hasOnsetsAnswer(songId: String): Boolean {
        val row = dao.getOnsets(songId) ?: return false
        return row.timesMs.isNotEmpty() || System.currentTimeMillis() - row.fetchedAt < ONSETS_RECHECK_MS
    }

    private suspend fun saveOnsets(
        songId: String,
        onsets: Onsets,
    ) {
        val now = System.currentTimeMillis()
        dao.upsertOnsets(OnsetsEntity(songId, encodeInts(onsets.timesMs), encodeInts(onsets.strengths), now))
    }

    /**
     * Decodes [songId] on the device when it is wholly in the audio cache ([sizeBytes] long), and no waveform is stored
     * yet. A track that is not whole is left alone; the prefetcher downloads it, and this runs again when it has.
     */
    suspend fun analyseCached(
        songId: String,
        sizeBytes: Long,
    ) {
        if (dao.get(songId) != null) return
        val peaks = decodeCached(songId, sizeBytes) ?: return
        dao.upsert(WaveformEntity(songId, encodePeaks(peaks), SOURCE_LOCAL, System.currentTimeMillis()))
    }

    /** Null when the track is not usable from the cache, or could not be read or decoded. */
    private suspend fun decodeCached(
        songId: String,
        sizeBytes: Long,
    ): List<Int>? {
        val settings = settingsProvider()
        val durationSec = tracks.get(songId)?.durationSec
        if (settings == null || durationSec == null || !audio.holdsWhole(songId, sizeBytes)) return null
        val source =
            CachedMediaDataSource(
                reader = audio.cacheOnlyFactory(),
                uri = Uri.parse(settings.streamUrl(songId).toString()),
                key = songId,
                length = sizeBytes,
            )
        val peaks =
            try {
                withContext(Dispatchers.Default) {
                    LocalWaveform.decode(source, durationSec * MILLIS_PER_SECOND, BINS)
                }
            } catch (e: IOException) {
                Log.d(TAG, "Local waveform skipped: ${e.javaClass.simpleName}")
                null
            }
        return if (source.failed) null else peaks
    }

    /** Removes every waveform and every onset list, when the connection changes. */
    suspend fun clear() {
        dao.deleteAll()
        dao.deleteAllOnsets()
    }

    companion object {
        /** Bins per waveform. Matches folksonic's default, so server and local waveforms look alike. */
        const val BINS = 150

        const val SOURCE_SERVER = "server"
        const val SOURCE_LOCAL = "local"

        /** How long a server's "no onsets" answer stands before the song is asked again. */
        const val ONSETS_RECHECK_MS = 24 * 60 * 60 * 1_000L

        private const val MAX_SERVER_ATTEMPTS = 4
        private const val MILLIS_PER_SECOND = 1_000L
        private const val TAG = "WaveformStore"
    }
}
