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

/** What the UI shows about playback. Derived from the media session; no playback state is stored here. */
data class PlaybackUiState(
    val nowPlayingTitle: String? = null,
    val nowPlayingArtist: String? = null,
    val isPlaying: Boolean = false,
    /** Speed of the current track. Pitch is kept constant by Media3's time-stretching. */
    val tempo: Float = 1f,
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
    /** The playback choices, read when a track starts. */
    private val playbackSettings: () -> PlaybackSettings = { PlaybackSettings() },
    /** Called on the main thread when a track starts; the argument is its song id. */
    private val onTrackStarted: (songId: String) -> Unit = {},
) {
    private val _state = MutableStateFlow(PlaybackUiState())
    val state: StateFlow<PlaybackUiState> = _state.asStateFlow()

    private var controller: MediaController? = null
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
                        applyCurrentTrack(built)
                        publish(built)
                        mediaItem?.mediaId?.let(onTrackStarted)
                    }

                    override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) = publish(built)
                },
            )
            controller = built
            publish(built)
            built
        }

    private fun publish(player: Player) {
        val metadata = player.mediaMetadata
        _state.value =
            PlaybackUiState(
                nowPlayingTitle = metadata.title?.toString(),
                nowPlayingArtist = metadata.artist?.toString(),
                isPlaying = player.isPlaying,
                tempo = player.playbackParameters.speed,
            )
    }

    /**
     * Speed and volume for the track now playing, from the playback settings. Called when a track starts and when
     * a setting changes, so the current track follows the change at once.
     */
    private fun applyCurrentTrack(player: MediaController) {
        val settings = playbackSettings()
        val entry = queue.getOrNull(player.currentMediaItemIndex)
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
        scope.launch(Dispatchers.Main) {
            val player = connected()
            player.setMediaItems(tracks.map { it.track.toMediaItem(settings) }, startIndex, 0L)
            player.prepare()
            applyCurrentTrack(player)
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
        controller?.release()
        controller = null
    }

    companion object {
        fun TrackEntity.toMediaItem(settings: ServerSettings): MediaItem =
            MediaItem.Builder()
                .setMediaId(id)
                .setUri(settings.streamUrl(id).toString())
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
