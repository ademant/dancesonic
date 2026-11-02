package org.dancesonic.player.playback

import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import org.dancesonic.player.DanceSonicApp

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
        val mediaSourceFactory = DefaultMediaSourceFactory(this)
            .setDataSourceFactory(OkHttpDataSource.Factory(container.httpClient))
        val player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(mediaSourceFactory)
            .setHandleAudioBecomingNoisy(true)
            .build()
        session = MediaSession.Builder(this, player).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onDestroy() {
        session?.let { mediaSession ->
            mediaSession.player.release()
            mediaSession.release()
        }
        session = null
        super.onDestroy()
    }
}
