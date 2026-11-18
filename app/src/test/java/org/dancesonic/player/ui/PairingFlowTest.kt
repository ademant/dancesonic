package org.dancesonic.player.ui

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.dancesonic.player.subsonic.ServerAuth
import org.dancesonic.player.subsonic.ServerSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The countdown while a pairing waits, and which old key a pairing revokes (#68). */
class PairingFlowTest {
    private val server = "https://music.example.com/".toHttpUrl()
    private val device = ServerSettings(server, ServerAuth.Device("alias-1"))

    @Test
    fun `the countdown shows minutes and seconds left, rounded up`() {
        assertEquals(
            "Waiting for approval on the account page. The code expires in 15:00.",
            waitingStatus(15 * 60_000L),
        )
        assertEquals("Waiting for approval on the account page. The code expires in 0:05.", waitingStatus(4_001))
        assertEquals("Waiting for approval on the account page. The code expires in 1:01.", waitingStatus(61_000))
    }

    @Test
    fun `at expiry the countdown says the code expired`() {
        assertEquals("The code expired. Create a new code on the account page.", waitingStatus(0))
        assertEquals("The code expired. Create a new code on the account page.", waitingStatus(-500))
    }

    @Test
    fun `pairing a device replaces and revokes the API key for the same server`() {
        val previous = ServerSettings(server, "old-key-123")

        assertEquals("old-key-123", replacedKeyToRevoke(previous, device))
    }

    @Test
    fun `nothing is revoked without an old key, for another server, or when a key replaces a key`() {
        assertNull(replacedKeyToRevoke(null, device))
        assertNull(replacedKeyToRevoke(ServerSettings(server, ServerAuth.Device("alias-0")), device))
        assertNull(replacedKeyToRevoke(ServerSettings("https://other.example.com/".toHttpUrl(), "old-key"), device))
        assertNull(replacedKeyToRevoke(ServerSettings(server, "old-key"), ServerSettings(server, "new-key")))
    }
}
