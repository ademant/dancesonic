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
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
)

/**
 * UI-side handle to [PlaybackService]. Commands go through a [MediaController], so playback
 * continues if the UI is destroyed.
 * Media3 marks its session API as unstable. The lint check is suppressed here rather than with an
 * opt-in marker, which would spread to every caller.
 */
@Suppress("UnsafeOptInUsageError")
class PlayerController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val settingsProvider: () -> ServerSettings?,
    /** The playback choices: read when a track starts, and saved when a new track resets the tempo. */
    private val settings: PlaybackSettingsSource,
    /** Called on the main thread when a track starts; the argument is its song id. */
    private val onTrackStarted: (songId: String) -> Unit = {},
    /**
     * Called with each new queue, before it starts: the tracks, the index that starts, and the server settings.
     * Records the sizes that bound the stream, and may start a download of the track that begins.
     */
    private val onQueued: (
        queue: List<TrackEntity>,
        startIndex: Int,
        settings: ServerSettings,
    ) -> Unit = { _, _, _ -> },
) {
    private val _state = MutableStateFlow(PlaybackUiState())
    val state: StateFlow<PlaybackUiState> = _state.asStateFlow()

    private val _progress = MutableStateFlow(PlaybackProgress())

    /** Position and length of the track now playing, refreshed twice a second while connected. */
    val progress: StateFlow<PlaybackProgress> = _progress.asStateFlow()

    private var controller: MediaController? = null
    private var ticker: Job? = null
    private val connectLock = Mutex()

    /** The queue as it was loaded, so a track's tempo and gain can be looked up by its index. */
    private var queue: List<TrackWithTempo> = emptyList()

    /** Connects once; concurrent first calls wait for the same connection. */
    private suspend fun connected(): MediaController =
        connectLock.withLock {
            controller?.let { return@withLock it }
            val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
            val built = awaitFuture(MediaController.Builder(context, token).buildAsync())
            built.addListener(
                object : Player.Listener {
                    override fun onIsPlayingChanged(isPlaying: Boolean) = publish(built)

                    override fun onMediaItemTransition(
                        mediaItem: MediaItem?,
                        reason: Int,
                    ) {
                        applyCurrentTrack(built, fromStart = true)
                        publish(built)
                        mediaItem?.mediaId?.let(onTrackStarted)
                    }

                    override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) = publish(built)
                },
            )
            controller = built
            publish(built)
            ticker?.cancel()
            ticker =
                scope.launch(Dispatchers.Main) {
                    while (true) {
                        publishProgress(built)
                        delay(PROGRESS_INTERVAL_MS)
                    }
                }
            built
        }

    private fun publishProgress(player: Player) {
        // Some streams never report a length; the server's length for the track is the fallback.
        val serverLengthMs =
            queue.getOrNull(
                player.currentMediaItemIndex,
            )?.track?.durationSec?.let { it * MILLIS_PER_SECOND }
        val duration = if (player.duration > 0) player.duration else serverLengthMs ?: player.duration
        _progress.value = PlaybackProgress.of(player.currentPosition, duration)
    }

    /** Moves the track now playing to [positionMs], and shows the new position at once. */
    fun seekTo(positionMs: Long) {
        scope.launch(Dispatchers.Main) {
            val player = connected()
            player.seekTo(positionMs)
            publishProgress(player)
        }
    }

    private fun publish(player: Player) {
        val metadata = player.mediaMetadata
        _state.value =
            PlaybackUiState(
                nowPlayingTitle = metadata.title?.toString(),
                nowPlayingArtist = metadata.artist?.toString(),
                nowPlayingId = player.currentMediaItem?.mediaId,
                isPlaying = player.isPlaying,
                tempo = player.playbackParameters.speed,
                trackBpm = queue.getOrNull(player.currentMediaItemIndex)?.tempoBpm,
            )
    }

    /**
     * Speed and volume for the track now playing, from the playback settings. Called when a track starts and when
     * a setting changes, so the current track follows the change at once.
     */
    private fun applyCurrentTrack(
        player: MediaController,
        fromStart: Boolean = false,
    ) {
        val entry = queue.getOrNull(player.currentMediaItemIndex)
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
        scope.launch(Dispatchers.Main) {
            val player = controller ?: return@launch
            applyCurrentTrack(player)
            publish(player)
        }
    }

    /** Replaces the queue with [tracks] and starts playing at [startIndex]. */
    fun play(
        tracks: List<TrackWithTempo>,
        startIndex: Int = 0,
    ) {
        val settings = settingsProvider() ?: return
        if (tracks.isEmpty()) return
        queue = tracks
        onQueued(tracks.map { it.track }, startIndex, settings)
        scope.launch(Dispatchers.Main) {
            val player = connected()
            player.setMediaItems(tracks.map { it.track.toMediaItem(settings) }, startIndex, 0L)
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

    fun release() {
        ticker?.cancel()
        ticker = null
        controller?.release()
        controller = null
    }

    companion object {
        /** How often the progress is read while connected. */
        private const val PROGRESS_INTERVAL_MS = 500L
        private const val MILLIS_PER_SECOND = 1_000L

        fun TrackEntity.toMediaItem(settings: ServerSettings): MediaItem =
            MediaItem.Builder()
                .setMediaId(id)
                .setUri(settings.streamUrl(id).toString())
                .setCustomCacheKey(id)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(title)
                        .setArtist(artistName)
                        .build(),
                )
                .build()

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
