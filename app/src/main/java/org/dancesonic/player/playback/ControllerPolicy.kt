package org.dancesonic.player.playback

import androidx.media3.common.Player

/** Who asks to control the media session, as Media3 identifies the caller. */
internal data class ControllerIdentity(
    val packageName: String,
    val uid: Int,
    /**
     * Media3's trust decision ([androidx.media3.session.MediaSession.ControllerInfo.isTrusted]): true for the system
     * and for apps holding `MEDIA_CONTENT_CONTROL` or an enabled notification listener. It is checked by the platform
     * with the caller's uid, not taken from a package name the caller reports.
     */
    val trusted: Boolean,
    /** True for the controller Media3 creates for this app's own media notification. */
    val mediaNotification: Boolean,
)

/** How much of the session a controller gets. */
internal enum class ControllerAccess {
    /** Every command: this app's own UI and its media notification. */
    FULL,

    /** Transport controls and what a lock screen or headset shows: the system's media controls, Bluetooth, cars. */
    TRANSPORT,

    /** Not connected. */
    REJECT,
}

/**
 * Decides which controllers may connect to [PlaybackService]'s session (#85). The service is exported so the system
 * can reach it, so any app could otherwise bind and read the queue and track metadata, or change the queue.
 *
 * - This app (same uid and package) and its media notification get full access.
 * - Controllers Media3 marks as trusted (system UI, Bluetooth, Android Auto) get [TRANSPORT_COMMANDS] and no session
 *   commands: play, pause, skip and seek, and the current item's metadata for the display.
 * - Every other controller is rejected.
 */
internal object ControllerPolicy {
    fun accessFor(
        caller: ControllerIdentity,
        ownPackage: String,
        ownUid: Int,
    ): ControllerAccess =
        when {
            caller.uid == ownUid && caller.packageName == ownPackage -> ControllerAccess.FULL
            caller.mediaNotification && caller.uid == ownUid -> ControllerAccess.FULL
            caller.trusted -> ControllerAccess.TRANSPORT
            else -> ControllerAccess.REJECT
        }

    /**
     * What a trusted system controller may do. Nothing that changes the queue, speed, volume or repeat mode, and no
     * read access beyond the current item, its metadata and the timeline that decides whether Next and Previous apply.
     */
    val TRANSPORT_COMMANDS: Player.Commands =
        Player.Commands
            .Builder()
            .addAll(
                Player.COMMAND_PLAY_PAUSE,
                Player.COMMAND_PREPARE,
                Player.COMMAND_STOP,
                Player.COMMAND_SEEK_TO_DEFAULT_POSITION,
                Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
                Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
                Player.COMMAND_SEEK_TO_PREVIOUS,
                Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                Player.COMMAND_SEEK_TO_NEXT,
                Player.COMMAND_SEEK_BACK,
                Player.COMMAND_SEEK_FORWARD,
                Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
                Player.COMMAND_GET_METADATA,
                Player.COMMAND_GET_TIMELINE,
            ).build()
}
