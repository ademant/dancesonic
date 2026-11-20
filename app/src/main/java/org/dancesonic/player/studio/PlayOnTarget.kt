package org.dancesonic.player.studio

import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.Player
import androidx.media3.session.MediaSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Decides whether playback runs on this phone or on one paired Studio. The media session keeps its controllers; only
 * the player behind it changes, so the notification, lock screen and the app's own controls follow the choice.
 *
 * Only one Studio is active at a time. Switching back to the phone leaves the Studio playing: the phone stops
 * controlling it and drops the connection. If Studio drops the connection, playback returns to the phone.
 *
 * Every method runs on the main thread.
 */
@Suppress("UnsafeOptInUsageError")
class PlayOnTarget(
    private val scope: CoroutineScope,
    private val clock: () -> Long = { SystemClock.elapsedRealtime() },
) {
    /** Where playback runs. Not stored: after an app restart playback is on the phone until the user chooses again. */
    sealed interface Target {
        data object Phone : Target

        data class Studio(val pairingId: String, val name: String) : Target
    }

    private val _target = MutableStateFlow<Target>(Target.Phone)
    val target: StateFlow<Target> = _target.asStateFlow()

    private var session: MediaSession? = null
    private var phone: Player? = null
    private var active: Active? = null

    private class Active(
        val pairingId: String,
        val connection: StudioSession,
        val player: StudioPlayer,
        val watch: Job,
    )

    /** Called by the playback service once its media session and phone player exist. */
    fun attach(
        mediaSession: MediaSession,
        phonePlayer: Player,
    ) {
        session = mediaSession
        phone = phonePlayer
    }

    /**
     * Makes [opened] the player behind the media session. The phone's playback stops. Returns false when the playback
     * service is not running, in which case nothing changes and [opened] must be closed by the caller.
     */
    fun playOnStudio(opened: OpenStudio): Boolean {
        val mediaSession = session ?: return false
        phone?.pause()
        if (active != null) playOnPhone()
        val player = StudioPlayer(Looper.getMainLooper(), opened.session, scope, clock, ::logRefused)
        val watch =
            scope.launch(Dispatchers.Main) {
                opened.session.ended.first { it != null }
                if (active?.connection === opened.session) playOnPhone()
            }
        mediaSession.player = player
        active = Active(opened.record.id, opened.session, player, watch)
        _target.value = Target.Studio(opened.record.id, opened.record.name)
        player.refresh()
        return true
    }

    /** Puts playback back on the phone. The Studio keeps playing; the connection to it closes. */
    fun playOnPhone() {
        val mediaSession = session
        val phonePlayer = phone
        if (active != null && mediaSession != null && phonePlayer != null) {
            mediaSession.player = phonePlayer
        }
        endActive()
        _target.value = Target.Phone
    }

    /** Called when the playback service stops: playback goes back to the phone so its player can be released. */
    fun detach() {
        playOnPhone()
        session = null
        phone = null
    }

    private fun endActive() {
        val current = active ?: return
        active = null
        current.watch.cancel()
        current.player.release()
        current.connection.close()
    }

    private fun logRefused(code: String) {
        Log.w(TAG, "Studio refused a command: $code")
    }

    private companion object {
        const val TAG = "PlayOnTarget"
    }
}
