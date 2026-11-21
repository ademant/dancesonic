package org.dancesonic.player.studio

import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.Player
import androidx.media3.session.MediaSession
import kotlinx.coroutines.CancellationException
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
 * controlling it and drops the connection. When the link to Studio drops, the phone opens the next transport and
 * carries on with the same Studio; if none answers, playback returns to the phone.
 *
 * Every method runs on the main thread.
 */
@Suppress("UnsafeOptInUsageError")
class PlayOnTarget(
    private val scope: CoroutineScope,
    /** Opens a new session with a paired Studio over the other transport, after [dropped] was lost. */
    private val reconnect: suspend (record: StudioPairing, dropped: String) -> OpenStudio,
    private val clock: () -> Long = { SystemClock.elapsedRealtime() },
) {
    /** Where playback runs. Not stored: after an app restart playback is on the phone until the user chooses again. */
    sealed interface Target {
        data object Phone : Target

        /** [transport] is `ble`, `relay` or `network`, as Studio names the link it runs on. */
        data class Studio(val pairingId: String, val name: String, val transport: String) : Target
    }

    private val _target = MutableStateFlow<Target>(Target.Phone)
    val target: StateFlow<Target> = _target.asStateFlow()

    private var session: MediaSession? = null
    private var phone: Player? = null
    private var active: Active? = null

    private class Active(
        val record: StudioPairing,
        val connection: StudioSession,
        val player: StudioPlayer,
        val transport: String,
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
        mediaSession.player = player
        active = Active(opened.record, opened.session, player, opened.transport, watch(opened))
        _target.value = Target.Studio(opened.record.id, opened.record.name, opened.transport)
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

    private fun watch(opened: OpenStudio): Job =
        scope.launch(Dispatchers.Main) {
            val reason = opened.session.ended.first { it != null }
            if (active?.connection !== opened.session) return@launch
            if (reason == StudioSession.LINK_LOST) {
                moveToOtherLink(opened)
            } else {
                playOnPhone()
            }
        }

    /**
     * Carries playback on Studio over to the other transport after the link on [lost] dropped. Studio's playback does
     * not depend on the link, so nothing is restarted. If the other transport cannot be reached, playback returns to
     * the phone.
     */
    private suspend fun moveToOtherLink(lost: OpenStudio) {
        val current = active ?: return
        val next =
            try {
                reconnect(current.record, lost.transport)
            } catch (e: CancellationException) {
                throw e
            } catch (e: StudioUnavailable) {
                Log.i(TAG, "Studio not reachable over the other transport: ${e.reason}")
                null
            }
        // The user may have chosen another target while the reconnect ran.
        if (active?.connection !== lost.session) {
            next?.session?.close()
            return
        }
        if (next == null) {
            playOnPhone()
            return
        }
        lost.session.close()
        current.player.switchSession(next.session)
        active = Active(current.record, next.session, current.player, next.transport, watch(next))
        _target.value = Target.Studio(current.record.id, current.record.name, next.transport)
        current.player.refresh()
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
