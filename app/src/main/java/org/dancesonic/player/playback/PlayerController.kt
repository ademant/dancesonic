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
    /** Playback speed. Pitch is kept constant by Media3's time-stretching. */
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
) {
    private val _state = MutableStateFlow(PlaybackUiState())
    val state: StateFlow<PlaybackUiState> = _state.asStateFlow()

    private var controller: MediaController? = null
    private val connectLock = Mutex()

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
                    ) = publish(built)

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

    /** Replaces the queue with [tracks] and starts playing at [startIndex]. */
    fun play(
        tracks: List<TrackEntity>,
        startIndex: Int = 0,
    ) {
        val settings = settingsProvider() ?: return
        if (tracks.isEmpty()) return
        scope.launch(Dispatchers.Main) {
            val player = connected()
            player.setMediaItems(tracks.map { it.toMediaItem(settings) }, startIndex, 0L)
            player.prepare()
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

    /** Sets playback speed. [speed] is clamped to the range the UI offers. */
    fun setTempo(speed: Float) {
        val clamped = speed.coerceIn(MIN_TEMPO, MAX_TEMPO)
        scope.launch(Dispatchers.Main) {
            connected().playbackParameters = PlaybackParameters(clamped, 1f)
        }
    }

    fun release() {
        controller?.release()
        controller = null
    }

    companion object {
        const val MIN_TEMPO = 0.5f
        const val MAX_TEMPO = 1.5f

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
