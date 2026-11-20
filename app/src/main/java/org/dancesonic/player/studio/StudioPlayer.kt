package org.dancesonic.player.studio

import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.SimpleBasePlayer.MediaItemData
import androidx.media3.common.SimpleBasePlayer.State
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * A Media3 player that plays on a paired Studio. It takes the place of the phone's player behind the media session, so
 * the notification, lock screen and the library screens control the Studio without changes. Commands become Studio
 * commands; Studio's `state` messages become the player's state.
 *
 * Its state is read on the thread of [looper], which must be the main thread. Studio's answers arrive on other threads
 * and are passed to the player through [Dispatchers.Main].
 */
@Suppress("UnsafeOptInUsageError", "TooManyFunctions")
class StudioPlayer(
    looper: Looper,
    session: StudioSession,
    private val scope: CoroutineScope,
    private val clock: () -> Long,
    private val onRefused: (String) -> Unit,
) : SimpleBasePlayer(looper) {
    /** Titles and other metadata the phone knows for song ids, from the items the phone put in the queue. */
    private val items = HashMap<String, MediaItem>()
    private var lastState: StudioPlayState? = null
    private var reportedAtMs = 0L
    private var session: StudioSession = session
    private var collector: Job? = null

    init {
        collector = follow(session)
    }

    /**
     * Moves the player to [next]: the same Studio on another link, with the counter carried on. Playback on Studio
     * is not interrupted. Call [refresh] afterwards to read Studio's current state.
     */
    fun switchSession(next: StudioSession) {
        collector?.cancel()
        session = next
        collector = follow(next)
    }

    private fun follow(current: StudioSession): Job =
        scope.launch(Dispatchers.Main) {
            current.state.collect { state ->
                if (state != null) {
                    lastState = state
                    reportedAtMs = clock()
                }
                invalidateState()
            }
        }

    /** Asks Studio for its current playback, so the player shows it at once. */
    fun refresh() = send(StudioCommand.GetState)

    override fun getState(): State {
        val studio = lastState
        val queue = studio?.queue.orEmpty()
        val playlist =
            queue.map { id ->
                val item = items[id] ?: MediaItem.Builder().setMediaId(id).build()
                MediaItemData.Builder(id).setMediaItem(item).build()
            }
        val index = (studio?.index ?: 0).coerceIn(0, (queue.size - 1).coerceAtLeast(0))
        return State.Builder()
            .setAvailableCommands(COMMANDS)
            .setPlaylist(playlist)
            .setCurrentMediaItemIndex(index)
            .setContentPositionMs { positionNow() }
            .setPlayWhenReady(studio?.playing == true, Player.PLAY_WHEN_READY_CHANGE_REASON_REMOTE)
            .setPlaybackState(if (queue.isEmpty()) Player.STATE_IDLE else Player.STATE_READY)
            .setPlaybackParameters(PlaybackParameters(studio?.rate?.toFloat() ?: 1f))
            .build()
    }

    private fun positionNow(): Long = lastState?.let { StudioPlayback.positionAt(it, reportedAtMs, clock()) } ?: 0L

    override fun handlePrepare(): ListenableFuture<*> = Futures.immediateVoidFuture()

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        send(if (playWhenReady) StudioCommand.Play else StudioCommand.Pause)
        return Futures.immediateVoidFuture()
    }

    override fun handleSeek(
        mediaItemIndex: Int,
        positionMs: Long,
        seekCommand: Int,
    ): ListenableFuture<*> {
        val studio = lastState
        val command =
            when {
                seekCommand == Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> StudioCommand.Next
                seekCommand == Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> StudioCommand.Previous
                mediaItemIndex != (studio?.index ?: 0) -> {
                    StudioCommand.PlaySongs(StudioPlayback.songsForCommand(studio?.queue.orEmpty()), mediaItemIndex)
                }
                else -> StudioCommand.Seek(positionMs)
            }
        send(command)
        return Futures.immediateVoidFuture()
    }

    override fun handleSetPlaybackParameters(playbackParameters: PlaybackParameters): ListenableFuture<*> {
        send(StudioCommand.SetRate(StudioPlayback.rateFor(playbackParameters.speed)))
        return Futures.immediateVoidFuture()
    }

    override fun handleSetMediaItems(
        mediaItems: List<MediaItem>,
        startIndex: Int,
        startPositionMs: Long,
    ): ListenableFuture<*> {
        val songs = remember(mediaItems)
        send(StudioCommand.PlaySongs(StudioPlayback.songsForCommand(songs), startIndex))
        return Futures.immediateVoidFuture()
    }

    override fun handleAddMediaItems(
        index: Int,
        mediaItems: List<MediaItem>,
    ): ListenableFuture<*> {
        val songs = remember(mediaItems)
        send(StudioCommand.Enqueue(StudioPlayback.songsForCommand(songs)))
        return Futures.immediateVoidFuture()
    }

    override fun handleStop(): ListenableFuture<*> {
        send(StudioCommand.Pause)
        return Futures.immediateVoidFuture()
    }

    override fun handleRelease(): ListenableFuture<*> {
        collector?.cancel()
        return Futures.immediateVoidFuture()
    }

    /** Keeps the items for their metadata and returns their song ids. */
    private fun remember(mediaItems: List<MediaItem>): List<String> =
        mediaItems.map { item ->
            items[item.mediaId] = item
            item.mediaId
        }

    @Suppress("TooGenericExceptionCaught", "SwallowedException") // a failed command is reported, never rethrown
    private fun send(command: StudioCommand) {
        scope.launch {
            try {
                session.send(command)
            } catch (e: CancellationException) {
                throw e
            } catch (e: StudioProtocolError) {
                onRefused(e.code)
            } catch (e: Exception) {
                onRefused("failed")
            }
        }
    }

    companion object {
        private val COMMANDS: Player.Commands =
            Player.Commands.Builder()
                .addAll(
                    Player.COMMAND_PREPARE,
                    Player.COMMAND_STOP,
                    Player.COMMAND_PLAY_PAUSE,
                    Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
                    Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
                    Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                    Player.COMMAND_SET_SPEED_AND_PITCH,
                    Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
                    Player.COMMAND_GET_TIMELINE,
                    Player.COMMAND_GET_METADATA,
                    Player.COMMAND_SET_MEDIA_ITEM,
                    Player.COMMAND_CHANGE_MEDIA_ITEMS,
                )
                .build()
    }
}
