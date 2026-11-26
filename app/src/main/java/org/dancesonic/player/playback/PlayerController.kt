package org.dancesonic.player.playback

import android.content.ComponentName
import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.dancesonic.player.data.db.TrackEntity
import org.dancesonic.player.data.db.TrackWithTempo
import org.dancesonic.player.subsonic.ServerSettings
import org.dancesonic.player.subsonic.streamUrl
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Where playback is in the current track, in milliseconds. [durationMs] is 0 while the length is unknown.
 */
data class PlaybackProgress(
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
) {
    companion object {
        /**
         * Keeps the position inside the track. Media3 reports an unknown length as a negative value, and a position can
         * briefly pass the end, so both are clamped here.
         */
        fun of(
            positionMs: Long,
            durationMs: Long,
        ): PlaybackProgress {
            val duration = durationMs.coerceAtLeast(0L)
            return PlaybackProgress(positionMs = positionMs.coerceIn(0L, duration), durationMs = duration)
        }
    }
}

/** Reads the playback choices, and saves the tempo a new track starts with when the tempo is not kept. */
interface PlaybackSettingsSource {
    fun current(): PlaybackSettings

    fun saveTempo(settings: PlaybackSettings)
}

/** What the UI shows about playback. Derived from the media session; no playback state is stored here. */
data class PlaybackUiState(
    val nowPlayingTitle: String? = null,
    val nowPlayingArtist: String? = null,
    /** The song id of the track now playing, which is its key in the local database. Null when nothing plays. */
    val nowPlayingId: String? = null,
    val isPlaying: Boolean = false,
    /** Speed of the current track. Pitch is kept constant by Media3's time-stretching. */
    val tempo: Float = 1f,
    /** The recording's own BPM, when the server has one. Null when unknown. */
    val trackBpm: Int? = null,
    /** How many tracks the queue holds; 0 when nothing has been queued. */
    val queueLength: Int = 0,
    val hasPrevious: Boolean = false,
    val hasNext: Boolean = false,
) {
    /** Nothing queued, so the transport controls have nothing to act on (#105). */
    val idle: Boolean get() = queueLength == 0
}

/**
 * UI-side handle to [PlaybackService]. Commands go through a [MediaController], so playback
 * continues if the UI is destroyed.
 * Media3 marks its session API as unstable. The lint check is suppressed here rather than with an
 * opt-in marker, which would spread to every caller.
 */
@Suppress("UnsafeOptInUsageError", "TooManyFunctions")
class PlayerController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val settingsProvider: () -> ServerSettings?,
    /** The playback choices: read when a track starts, and saved when a new track resets the tempo. */
    private val settings: PlaybackSettingsSource,
    /** What playback reports to the app: a track starting, a new queue, and a counted play (#89). */
    private val events: PlaybackEvents = PlaybackEvents.None,
) {
    private val playCounter =
        PlayCounter(System::currentTimeMillis) { songId, startedAtMs -> events.playCounted(songId, startedAtMs) }

    /** Reads the state, queue and progress from the session, and runs the progress ticker while playing. */
    private val publisher =
        PlaybackPublisher(scope, playCounter, onTrackStarted = { player, songId ->
            applyCurrentTrack(player, fromStart = true)
            events.trackStarted(songId)
        })

    /** What the UI shows about playback. */
    val state: StateFlow<PlaybackUiState> = publisher.state

    /** Position and length of the track now playing, refreshed twice a second while it plays. */
    val progress: StateFlow<PlaybackProgress> = publisher.progress

    /** The songs in playback order, including those already played and those still to come. */
    val queue: StateFlow<PlayQueue> = publisher.queue

    /** The connection to the service. [reset] closes it, and work started before a reset sees that it is stale. */
    private val slot =
        ControllerSlot {
            val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
            awaitFuture(MediaController.Builder(context, token).buildAsync())
        }

    private val controller: MediaController? get() = slot.connection

    /** Connects once; concurrent first calls wait for the same connection. */
    private suspend fun connected(): MediaController = slot.get(publisher::attach)

    /** Connects to the playback service now, starting it if needed. Used before playback is handed to a Studio. */
    suspend fun ensureConnected() {
        connected()
    }

    /** Moves the track now playing to [positionMs], and shows the new position at once. */
    fun seekTo(positionMs: Long) {
        scope.launch(Dispatchers.Main) {
            val player = connected()
            player.seekTo(positionMs)
            publisher.publishProgress(player)
        }
    }

    /**
     * Speed and volume for the track now playing, from the playback settings. Called when a track starts and when
     * a setting changes, so the current track follows the change at once. The track is found by its media id, so a
     * reordered queue keeps each song's tempo and gain (#129).
     */
    private fun applyCurrentTrack(
        player: Player,
        fromStart: Boolean = false,
    ) {
        val entry = publisher.current(player)
        val current = settings.current()
        val settings =
            if (fromStart && !current.keepTempoForNextTrack) {
                PlaybackMath.trackStartSettings(current, entry?.tempoBpm).also {
                    if (it != current) settings.saveTempo(it)
                }
            } else {
                current
            }
        player.playbackParameters = PlaybackParameters(PlaybackMath.speed(settings, entry?.tempoBpm), 1f)
        player.volume =
            PlaybackMath.volume(
                replayGainEnabled = settings.replayGain,
                trackGainDb = entry?.track?.trackGain,
                trackPeak = entry?.track?.trackPeak,
            )
    }

    /** Called after a playback setting changed: the track now playing takes the new speed and volume. */
    fun settingsChanged() {
        // Called from the settings collector on a background thread. Main.immediate still moves the work to the main
        // thread there; it also runs on a test's Unconfined main dispatcher, which refuses dispatches from other
        // threads and failed unrelated tests with UncaughtExceptionsBeforeTest.
        scope.launch(Dispatchers.Main.immediate) {
            val player = controller ?: return@launch
            applyCurrentTrack(player)
            publisher.publish(player)
        }
    }

    /** Replaces the queue with [tracks] and starts playing at [startIndex]. */
    fun play(
        tracks: List<TrackWithTempo>,
        startIndex: Int = 0,
    ) {
        val settings = settingsProvider() ?: return
        if (tracks.isEmpty()) return
        val generation = slot.generation
        publisher.load(tracks)
        events.queued(tracks.map { it.track }, startIndex, settings)
        scope.launch(Dispatchers.Main) {
            val player = connected()
            // A reset since this call (a disconnect or another server) drops this queue: it belongs to the old server.
            if (!slot.isCurrent(generation)) return@launch
            player.setMediaItems(tracks.map { it.track.toMediaItem(settings) }, startIndex, 0L)
            publisher.publishQueue(player)
            player.prepare()
            applyCurrentTrack(player, fromStart = true)
            player.play()
        }
    }

    fun togglePlayPause() {
        scope.launch(Dispatchers.Main) {
            val player = connected()
            if (player.isPlaying) player.pause() else player.play()
        }
    }

    fun next() {
        scope.launch(Dispatchers.Main) { connected().seekToNextMediaItem() }
    }

    fun previous() {
        scope.launch(Dispatchers.Main) { connected().seekToPreviousMediaItem() }
    }

    /**
     * Starts the song at [index] in the current queue, leaving the other songs in place. Connects first when the
     * session is not connected yet (#129); an index outside the session's queue does nothing.
     */
    fun playAt(index: Int) {
        scope.launch(Dispatchers.Main) {
            val player = connected()
            if (index !in 0 until player.mediaItemCount) return@launch
            player.seekTo(index, 0L)
            publisher.publishQueue(player)
        }
    }

    /**
     * Stops playback and forgets everything about it (#130): the queue, the track and tempo shown, the progress, and
     * the tracks loaded. Called when the app disconnects or switches to another server, whose songs these are not. The
     * connection to the service is closed and its player emptied; a connection still being opened is closed as soon as
     * it opens, and a [play] that started before the reset does nothing. Safe to call repeatedly. The progress ticker
     * stops with it (#136).
     */
    fun reset() {
        slot.invalidate()
        publisher.clear()
        scope.launch(Dispatchers.Main.immediate) {
            slot.close { player ->
                player.stop()
                player.clearMediaItems()
                player.release()
            }
            playCounter.stopped()
            // The old player may have published once more before it was released.
            publisher.clear()
        }
    }

    companion object {
        fun TrackEntity.toMediaItem(settings: ServerSettings): MediaItem =
            MediaItem
                .Builder()
                .setMediaId(id)
                .setUri(settings.streamUrl(id).toString())
                .setCustomCacheKey(id)
                .setMediaMetadata(
                    MediaMetadata
                        .Builder()
                        .setTitle(title)
                        .setArtist(artistName)
                        .build(),
                ).build()

        private suspend fun <T> awaitFuture(future: ListenableFuture<T>): T =
            suspendCancellableCoroutine { cont ->
                future.addListener(
                    {
                        try {
                            cont.resume(future.get())
                        } catch (e: ExecutionException) {
                            cont.resumeWithException(e.cause ?: e)
                        } catch (e: CancellationException) {
                            cont.cancel(e)
                        }
                    },
                    { it.run() },
                )
                cont.invokeOnCancellation { future.cancel(true) }
            }
    }
}
