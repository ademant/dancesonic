package org.dancesonic.player.playback

import android.os.Process
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommands
import org.dancesonic.player.DanceSonicApp
import org.dancesonic.player.playback.stretch.OnsetCache
import org.dancesonic.player.playback.stretch.StreamTracker
import org.dancesonic.player.playback.stretch.StretchRenderersFactory

/**
 * Owns the ExoPlayer instance and the media session. Being a [MediaSessionService] keeps audio
 * playing while the app is in the background and exposes the notification and lock-screen controls.
 */
@UnstableApi
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val container = (application as DanceSonicApp).container
        // Streams go through the same authenticated OkHttp client as the API; the API key is added
        // by the interceptor, so media items carry no secrets.
        val mediaSourceFactory =
            DefaultMediaSourceFactory(this)
                .setDataSourceFactory(
                    container.audioCache.dataSourceFactory(
                        StreamDataSource.Factory(
                            OkHttpDataSource.Factory(container.httpClient),
                            sizeOf = container.streamLengths::sizeOf,
                        ),
                    ),
                )
        val tracker = StreamTracker()
        val onsets = container.onsetCache
        val player =
            ExoPlayer
                .Builder(this, StretchRenderersFactory(this, tracker, onsets::onsetsFor))
                .setMediaSourceFactory(mediaSourceFactory)
                .withMusicAudio()
                .build()
        player.addListener(QueueFollower(tracker, onsets))
        val mediaSession =
            MediaSession
                .Builder(this, player)
                .setCallback(ControllerGate(packageName, Process.myUid()))
                .build()
        session = mediaSession
        container.playOn.attach(mediaSession, player)
    }

    /**
     * Lets only this app, its notification and trusted system controllers connect; see [ControllerPolicy]. Media3 1.9
     * has only the synchronous [MediaSession.Callback.onConnect]; the decision needs no I/O, so that is enough.
     */
    private class ControllerGate(
        private val ownPackage: String,
        private val ownUid: Int,
    ) : MediaSession.Callback {
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): MediaSession.ConnectionResult {
            val caller =
                ControllerIdentity(
                    packageName = controller.packageName,
                    uid = controller.uid,
                    trusted = controller.isTrusted,
                    mediaNotification = session.isMediaNotificationController(controller),
                )
            return when (ControllerPolicy.accessFor(caller, ownPackage, ownUid)) {
                ControllerAccess.FULL -> MediaSession.ConnectionResult.AcceptedResultBuilder(session).build()
                ControllerAccess.TRANSPORT ->
                    MediaSession.ConnectionResult
                        .AcceptedResultBuilder(session)
                        .setAvailableSessionCommands(SessionCommands.EMPTY)
                        .setAvailablePlayerCommands(ControllerPolicy.TRANSPORT_COMMANDS)
                        .build()
                ControllerAccess.REJECT -> {
                    Log.d(TAG, "Media controller rejected: ${controller.packageName}")
                    MediaSession.ConnectionResult.reject()
                }
            }
        }
    }

    /**
     * Tells the time-stretch which songs play now and next, and keeps their onsets loaded. Runs on the application
     * thread as the queue, the current item, repeat or shuffle change.
     */
    private class QueueFollower(
        private val tracker: StreamTracker,
        private val onsets: OnsetCache,
    ) : Player.Listener {
        override fun onEvents(
            player: Player,
            events: Player.Events,
        ) {
            if (QUEUE_EVENTS.none(events::contains)) return
            val current = player.currentMediaItem?.mediaId
            val nextIndex = player.nextMediaItemIndex
            val next = if (nextIndex == C.INDEX_UNSET) null else player.getMediaItemAt(nextIndex).mediaId
            tracker.setQueue(current, next)
            onsets.prefetch(setOfNotNull(current, next))
        }

        private companion object {
            val QUEUE_EVENTS =
                intArrayOf(
                    Player.EVENT_MEDIA_ITEM_TRANSITION,
                    Player.EVENT_TIMELINE_CHANGED,
                    Player.EVENT_REPEAT_MODE_CHANGED,
                    Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED,
                )
        }
    }

    private companion object {
        const val TAG = "PlaybackService"
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onDestroy() {
        (application as DanceSonicApp).container.playOn.detach()
        session?.let { mediaSession ->
            mediaSession.player.release()
            mediaSession.release()
        }
        session = null
        super.onDestroy()
    }
}
