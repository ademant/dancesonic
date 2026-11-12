package org.dancesonic.player.ui

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.dancesonic.player.subsonic.ServerAuth
import org.dancesonic.player.subsonic.ServerSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A device is checked once: the header upgrade, which needs a second check, is for API keys only. */
class SettingsAfterProbeTest {
    private val url = "https://cloud.example.com/folksonic/".toHttpUrl()

    @Test
    fun `an API key on folksonic moves to the header`() {
        val settled = settingsAfterProbe(ServerSettings(url, "key-123"), advertisesTinysonic = true)

        assertTrue(settled.keyInHeader)
    }

    @Test
    fun `a device is not moved to a header and is not checked again`() {
        val entered = ServerSettings(url, ServerAuth.Device("alias-1"))

        val settled = settingsAfterProbe(entered, advertisesTinysonic = true)

        assertFalse(settled.keyInHeader)
        assertEquals(entered.auth, settled.auth)
    }

    @Test
    fun `an API key on another server keeps the query`() {
        assertFalse(settingsAfterProbe(ServerSettings(url, "key-123"), advertisesTinysonic = false).keyInHeader)
    }
}
