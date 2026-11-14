package org.dancesonic.player.playback.stretch

import android.database.sqlite.SQLiteException
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.dancesonic.player.analysis.Onsets
import java.util.concurrent.ConcurrentHashMap

/**
 * The onsets of the songs around the playing one, held in memory so the audio thread reads them without waiting for
 * the database. [prefetch] loads the songs it is given and forgets the rest; [onsetsFor] never blocks.
 */
class OnsetCache(
    private val scope: CoroutineScope,
    private val load: suspend (String) -> Onsets?,
) {
    private val loaded = ConcurrentHashMap<String, Onsets>()

    @Volatile
    private var wanted: Set<String> = emptySet()

    /** The onsets of [songId] if they are loaded, else null. Safe to call from any thread. */
    fun onsetsFor(songId: String): Onsets? = loaded[songId]

    /** Keeps [songIds] loaded, loading any that are missing, and drops every other song. */
    fun prefetch(songIds: Set<String>) {
        wanted = songIds
        loaded.keys.retainAll(songIds)
        songIds.filterNot(loaded::containsKey).forEach(::refresh)
    }

    /** Loads [songId] again, for example after its onsets were fetched from the server. */
    fun refresh(songId: String) {
        scope.launch {
            val onsets =
                try {
                    load(songId)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: SQLiteException) {
                    // Without its onsets the track still plays, stretched uniformly.
                    Log.d(TAG, "Onsets not loaded: ${e.javaClass.simpleName}")
                    null
                }
            if (onsets != null && songId in wanted) loaded[songId] = onsets
        }
    }

    private companion object {
        const val TAG = "OnsetCache"
    }
}
