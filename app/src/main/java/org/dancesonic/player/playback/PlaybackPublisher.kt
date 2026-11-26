package org.dancesonic.player.playback

import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.dancesonic.player.data.db.TrackWithTempo

/**
 * What the UI shows about the player: its state, its queue and its progress, read from a [Player] whenever it reports
 * a change. Split from [PlayerController] so it can be tested with a real player.
 *
 * A track's tempo, length and gain are looked up by its media id, not by its position, so they stay with the song when
 * another controller reorders the session (#129). The progress ticker runs only while the player plays (#136).
 */
internal class PlaybackPublisher(
    private val scope: CoroutineScope,
    private val playCounter: PlayCounter,
    /** Called when a track starts, after the state is published: the speed and volume are set there. */
    private val onTrackStarted: (player: Player, songId: String) -> Unit,
    private val main: CoroutineDispatcher = Dispatchers.Main,
    private val intervalMs: Long = PROGRESS_INTERVAL_MS,
) {
    private val _state = MutableStateFlow(PlaybackUiState())
    val state: StateFlow<PlaybackUiState> = _state.asStateFlow()

    private val _progress = MutableStateFlow(PlaybackProgress())
    val progress: StateFlow<PlaybackProgress> = _progress.asStateFlow()

    private val _queue = MutableStateFlow(PlayQueue())
    val queue: StateFlow<PlayQueue> = _queue.asStateFlow()

    /** The tracks as they were loaded, by song id. */
    @Volatile
    private var loaded: Map<String, TrackWithTempo> = emptyMap()

    private var ticker: Job? = null

    /** Whether the progress ticker runs now. */
    val ticking: Boolean get() = ticker?.isActive == true

    /** Remembers the tracks of a new queue, so their tempo and length can be found by song id. */
    fun load(tracks: List<TrackWithTempo>) {
        loaded = tracks.associateBy { it.track.id }
    }

    /** The loaded track that [player] plays now, or null. */
    fun current(player: Player): TrackWithTempo? = player.currentMediaItem?.mediaId?.let { loaded[it] }

    /** The loaded track with this song id, or null. */
    fun track(songId: String): TrackWithTempo? = loaded[songId]

    /** Follows [player]: publishes its state now and on every change. */
    fun attach(player: Player) {
        player.addListener(listenerFor(player))
        publish(player)
        publishQueue(player)
        publishProgress(player)
        if (player.isPlaying) startTicker(player)
    }

    /** The listener [attach] adds. Tests call it directly for events a test player cannot produce. */
    internal fun listenerFor(player: Player): Player.Listener =
        object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                publish(player)
                if (isPlaying) startTicker(player) else stopTicker(player)
            }

            override fun onMediaItemTransition(
                mediaItem: MediaItem?,
                reason: Int,
            ) {
                publish(player)
                publishQueue(player)
                val songId = mediaItem?.mediaId
                if (songId == null) {
                    playCounter.stopped()
                } else {
                    playCounter.trackStarted(songId, lengthMs(songId))
                    onTrackStarted(player, songId)
                    publish(player)
                }
            }

            override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) = publish(player)

            // A new, reordered or emptied queue changes the list and what the transport controls can do.
            override fun onTimelineChanged(
                timeline: Timeline,
                reason: Int,
            ) {
                publish(player)
                publishQueue(player)
            }
        }

    /** Publishes what [player] plays now. */
    fun publish(player: Player) {
        val metadata = player.mediaMetadata
        _state.value =
            PlaybackUiState(
                nowPlayingTitle = metadata.title?.toString(),
                nowPlayingArtist = metadata.artist?.toString(),
                nowPlayingId = player.currentMediaItem?.mediaId,
                isPlaying = player.isPlaying,
                tempo = player.playbackParameters.speed,
                trackBpm = current(player)?.tempoBpm,
                queueLength = player.mediaItemCount,
                hasPrevious = player.hasPreviousMediaItem(),
                hasNext = player.hasNextMediaItem(),
            )
    }

    /** Publishes the media items of the session, so the player screen can list what played and what is next. */
    fun publishQueue(player: Player) {
        val items = List(player.mediaItemCount) { player.getMediaItemAt(it) }
        _queue.value = playQueueOf(items, player.currentMediaItemIndex)
    }

    /** Publishes the position. Some streams never report a length; the server's length is the fallback. */
    fun publishProgress(player: Player) {
        val serverLengthMs =
            player.currentMediaItem
                ?.mediaId
                ?.let { lengthMs(it) }
                ?.takeIf { it > 0 }
        val duration = if (player.duration > 0) player.duration else serverLengthMs ?: player.duration
        _progress.value = PlaybackProgress.of(player.currentPosition, duration)
    }

    /** Stops the ticker and forgets everything shown. */
    fun clear() {
        ticker?.cancel()
        ticker = null
        loaded = emptyMap()
        _state.value = PlaybackUiState()
        _progress.value = PlaybackProgress()
        _queue.value = PlayQueue()
    }

    /** The length of song [songId] in milliseconds, as the server gave it; 0 when it gave none. */
    private fun lengthMs(songId: String): Long =
        loaded[songId]?.track?.durationSec?.let { it * MILLIS_PER_SECOND } ?: 0L

    private fun startTicker(player: Player) {
        if (ticking) return
        // The time since the last tick was not spent playing, so the next tick starts counting from now.
        playCounter.tick(playing = false, speed = player.playbackParameters.speed)
        ticker =
            scope.launch(main) {
                while (true) {
                    delay(intervalMs)
                    publishProgress(player)
                    playCounter.tick(player.isPlaying, player.playbackParameters.speed)
                }
            }
    }

    private fun stopTicker(player: Player) {
        val running = ticker ?: return
        running.cancel()
        ticker = null
        // The time up to the pause was spent playing; the bar settles at the true position.
        playCounter.tick(playing = true, speed = player.playbackParameters.speed)
        publishProgress(player)
    }

    companion object {
        /** How often the progress is read while playing. */
        const val PROGRESS_INTERVAL_MS = 500L
        private const val MILLIS_PER_SECOND = 1_000L
    }
}
