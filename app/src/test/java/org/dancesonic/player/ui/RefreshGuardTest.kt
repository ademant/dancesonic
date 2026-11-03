package org.dancesonic.player.ui

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.dancesonic.player.subsonic.ServerSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class RefreshGuardTest {
    @Test
    fun `refresh without saved settings gives a message instead of silently returning`() {
        val reason = refreshBlockedReason(null)
        assertNotNull(reason)
        assertEquals("Not connected to a server. Connect again to sync.", reason)
    }

    @Test
    fun `refresh with saved settings may start`() {
        val settings = ServerSettings("https://music.example.com/".toHttpUrl(), "key-123")
        assertNull(refreshBlockedReason(settings))
    }
}
