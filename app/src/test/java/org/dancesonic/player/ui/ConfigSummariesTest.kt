package org.dancesonic.player.ui

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.dancesonic.player.playback.DownloadBlock
import org.dancesonic.player.playback.DownloadSettings
import org.dancesonic.player.playback.OfflineStatus
import org.dancesonic.player.playback.PlaybackSettings
import org.dancesonic.player.playback.TempoMode
import org.dancesonic.player.studio.PlayOnTarget
import org.dancesonic.player.studio.StudioPairing
import org.dancesonic.player.subsonic.ServerAuth
import org.dancesonic.player.subsonic.ServerSettings
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import java.util.Locale

/** The one-line summaries under the collapsed Config sections (#108). */
class ConfigSummariesTest {
    private val savedLocale = Locale.getDefault()

    @Before
    fun pinLocale() = Locale.setDefault(Locale.US)

    @After
    fun restoreLocale() = Locale.setDefault(savedLocale)

    private val server = "https://music.example.com/".toHttpUrl()

    @Test
    fun `the connection summary says how the app is connected, without the address or the key`() {
        val keyed = ServerSettings(server, "secret-key-123")
        val summary = connectionSummary(keyed, busy = false)

        assertEquals("Connected with an API key", summary)
        assertFalse(summary.contains("music.example.com"))
        assertFalse(summary.contains("secret-key-123"))
        assertEquals(
            "Connected as a paired device",
            connectionSummary(ServerSettings(server, ServerAuth.Device("device-alias")), busy = false),
        )
        assertEquals("Not connected", connectionSummary(null, busy = false))
        assertEquals("Connecting or syncing…", connectionSummary(keyed, busy = true))
    }

    @Test
    fun `a plain http connection is named as unencrypted`() {
        val lan = ServerSettings("http://192.168.1.20/".toHttpUrl(), "k", allowCleartext = true)

        assertEquals("Connected with an API key, unencrypted http", connectionSummary(lan, busy = false))
    }

    @Test
    fun `the playback summary shows the tempo mode with its value and ReplayGain`() {
        assertEquals("Tempo 1.00x, ReplayGain off", playbackSummary(PlaybackSettings()))
        assertEquals(
            "Tempo 120 BPM, ReplayGain on",
            playbackSummary(PlaybackSettings(replayGain = true, tempoMode = TempoMode.Bpm, targetBpm = 120)),
        )
    }

    @Test
    fun `the offline summary counts the kept tracks on the phone and says when downloads wait`() {
        val noMobile = DownloadSettings(allowMobileData = false)

        assertEquals("No tracks kept offline", offlineSummary(OfflineStatus(), noMobile))
        assertEquals(
            "12 of 12 kept tracks on the phone",
            offlineSummary(OfflineStatus(keptTracks = 12, heldTracks = 12), noMobile),
        )
        assertEquals(
            "3 of 12 kept tracks on the phone, more on Wi-Fi",
            offlineSummary(OfflineStatus(keptTracks = 12, heldTracks = 3), noMobile),
        )
        assertEquals(
            "3 of 12 kept tracks on the phone, downloads paused",
            offlineSummary(OfflineStatus(keptTracks = 12, heldTracks = 3, blocked = DownloadBlock.Offline), noMobile),
        )
    }

    @Test
    fun `the Studio summary names where playback runs`() {
        val pairing =
            StudioPairing(
                id = "p1",
                name = "Hall laptop",
                fingerprint = "fp",
                studioNoiseKey = "nk",
                bleService = "uuid",
                deviceId = "d1",
                keyAlias = "alias",
                phoneNoiseKey = "pk",
            )

        assertEquals("No Studio paired", studioSummary(emptyList(), PlayOnTarget.Target.Phone))
        assertEquals(
            "1 Studio paired, playing on this phone",
            studioSummary(listOf(pairing), PlayOnTarget.Target.Phone),
        )
        assertEquals(
            "2 Studios paired, playing on this phone",
            studioSummary(listOf(pairing, pairing.copy(id = "p2")), PlayOnTarget.Target.Phone),
        )
        assertEquals(
            "Playing on Hall laptop over Bluetooth",
            studioSummary(listOf(pairing), PlayOnTarget.Target.Studio("p1", "Hall laptop", "ble")),
        )
    }

    @Test
    fun `transports have readable names`() {
        assertEquals("Bluetooth", transportLabel("ble"))
        assertEquals("folksonic relay", transportLabel("relay"))
        assertEquals("Wi-Fi", transportLabel("network"))
    }

    @Test
    fun `the DanceBrainz summary says whether the app is paired`() {
        assertEquals("Paired", danceBrainzSummary(signedIn = true))
        assertEquals("Not paired", danceBrainzSummary(signedIn = false))
    }
}
