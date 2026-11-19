package org.dancesonic.player.playback

import androidx.media3.common.Player
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which media controllers may connect to the playback session, and with what (#85). */
class ControllerPolicyTest {
    private val ownPackage = "org.dancesonic.player"
    private val ownUid = 10_123

    private fun access(
        packageName: String,
        uid: Int,
        trusted: Boolean = false,
        notification: Boolean = false,
    ) = ControllerPolicy.accessFor(ControllerIdentity(packageName, uid, trusted, notification), ownPackage, ownUid)

    @Test
    fun `this app's own controller gets full access`() {
        assertEquals(ControllerAccess.FULL, access(ownPackage, ownUid))
    }

    @Test
    fun `the app's media notification gets full access`() {
        assertEquals(
            ControllerAccess.FULL,
            access("android.media.session.MediaController", ownUid, notification = true),
        )
    }

    @Test
    fun `a trusted system controller gets the transport controls only`() {
        assertEquals(ControllerAccess.TRANSPORT, access("com.android.systemui", uid = 10_050, trusted = true))
        assertEquals(ControllerAccess.TRANSPORT, access("com.android.bluetooth", uid = 1_002, trusted = true))
    }

    @Test
    fun `an untrusted app is rejected`() {
        assertEquals(ControllerAccess.REJECT, access("com.example.other", uid = 10_999))
    }

    @Test
    fun `an app that reports this app's package name from another uid is not trusted for it`() {
        assertEquals(ControllerAccess.REJECT, access(ownPackage, uid = 10_999))
    }

    @Test
    fun `a notification flag from another uid does not give full access`() {
        assertEquals(ControllerAccess.REJECT, access("com.example.other", uid = 10_999, notification = true))
    }

    @Test
    fun `the transport controls play, pause, skip and seek, and show the current track`() {
        val commands = ControllerPolicy.TRANSPORT_COMMANDS

        assertTrue(commands.contains(Player.COMMAND_PLAY_PAUSE))
        assertTrue(commands.contains(Player.COMMAND_SEEK_TO_NEXT))
        assertTrue(commands.contains(Player.COMMAND_SEEK_TO_PREVIOUS))
        assertTrue(commands.contains(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM))
        assertTrue(commands.contains(Player.COMMAND_GET_METADATA))
    }

    @Test
    fun `the transport controls cannot change the queue, the speed or the volume`() {
        val commands = ControllerPolicy.TRANSPORT_COMMANDS

        assertFalse(commands.contains(Player.COMMAND_CHANGE_MEDIA_ITEMS))
        assertFalse(commands.contains(Player.COMMAND_SET_MEDIA_ITEM))
        assertFalse(commands.contains(Player.COMMAND_SET_SPEED_AND_PITCH))
        assertFalse(commands.contains(Player.COMMAND_SET_VOLUME))
        assertFalse(commands.contains(Player.COMMAND_SET_REPEAT_MODE))
        assertFalse(commands.contains(Player.COMMAND_SET_SHUFFLE_MODE))
        assertFalse(commands.contains(Player.COMMAND_RELEASE))
    }
}
