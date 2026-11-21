package org.dancesonic.player.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.dancesonic.player.playback.DownloadSettings
import org.dancesonic.player.playback.OfflineStatus
import org.dancesonic.player.playback.PlaybackSettings
import org.dancesonic.player.playback.TempoMode
import org.dancesonic.player.studio.PlayOnTarget
import org.dancesonic.player.studio.StudioPairing
import org.dancesonic.player.subsonic.ServerAuth
import org.dancesonic.player.subsonic.ServerSettings

// One-line summaries under the collapsed Config sections (#108). They name a state, never a secret: no API key, no
// device alias and no server address.

/** The summaries of the Config sections that have state to show. */
internal data class ConfigSummaries(
    val connection: String,
    val playback: String,
    val offline: String,
    val studio: String,
    val danceBrainz: String,
)

/** Collects what the summaries need, so they update as their settings and the connection change. */
@Composable
internal fun configSummaries(
    connectVm: ConnectViewModel,
    playbackVm: PlaybackSettingsViewModel,
    offlineVm: OfflineViewModel,
    studioVm: StudioViewModel,
    danceVm: DanceBrainzViewModel,
): ConfigSummaries {
    val settings by connectVm.settings.collectAsStateWithLifecycle()
    val state by connectVm.state.collectAsStateWithLifecycle()
    val playback by playbackVm.settings.collectAsStateWithLifecycle()
    val offline by offlineVm.status.collectAsStateWithLifecycle()
    val downloads by offlineVm.settings.collectAsStateWithLifecycle()
    val pairings by studioVm.pairings.collectAsStateWithLifecycle()
    val target by studioVm.target.collectAsStateWithLifecycle()
    val signedIn by danceVm.signedIn.collectAsStateWithLifecycle()
    return ConfigSummaries(
        connection = connectionSummary(settings, state.busy),
        playback = playbackSummary(playback),
        offline = offlineSummary(offline, downloads),
        studio = studioSummary(pairings, target),
        danceBrainz = danceBrainzSummary(signedIn),
    )
}

/** The connection: whether there is one, how the app signs in, and whether it is unencrypted. */
internal fun connectionSummary(
    settings: ServerSettings?,
    busy: Boolean,
): String {
    if (busy) return "Connecting or syncing…"
    if (settings == null) return "Not connected"
    val how = if (settings.auth is ServerAuth.Device) "as a paired device" else "with an API key"
    val plain = if (settings.isCleartext) ", unencrypted http" else ""
    return "Connected $how$plain"
}

/** The tempo mode with its value, and ReplayGain. */
internal fun playbackSummary(settings: PlaybackSettings): String {
    val tempo =
        when (settings.tempoMode) {
            TempoMode.Bpm -> "Tempo ${settings.targetBpm} BPM"
            TempoMode.Multiplier -> "Tempo ${"%.2f".format(settings.multiplier)}x"
        }
    val gain = if (settings.replayGain) "ReplayGain on" else "ReplayGain off"
    return "$tempo, $gain"
}

/** How many kept tracks are on the phone, and whether downloads wait. */
internal fun offlineSummary(
    status: OfflineStatus,
    settings: DownloadSettings,
): String {
    if (status.keptTracks == 0) return "No tracks kept offline"
    val held = "${status.heldTracks} of ${status.keptTracks} kept tracks on the phone"
    return when {
        status.blocked != null -> "$held, downloads paused"
        status.heldTracks < status.keptTracks && !settings.allowMobileData -> "$held, more on Wi-Fi"
        else -> held
    }
}

/** Where playback runs, and how many Studios are paired. */
internal fun studioSummary(
    pairings: List<StudioPairing>,
    target: PlayOnTarget.Target,
): String =
    when {
        target is PlayOnTarget.Target.Studio -> "Playing on ${target.name} over ${transportLabel(target.transport)}"
        pairings.isEmpty() -> "No Studio paired"
        pairings.size == 1 -> "1 Studio paired, playing on this phone"
        else -> "${pairings.size} Studios paired, playing on this phone"
    }

/** Whether the app is paired with DanceBrainz for edits. */
internal fun danceBrainzSummary(signedIn: Boolean): String = if (signedIn) "Paired" else "Not paired"

/** The name of a Studio transport, as Studio reports it (`ble`, `relay` or `network`). */
internal fun transportLabel(transport: String): String =
    when (transport) {
        "ble" -> "Bluetooth"
        "relay" -> "folksonic relay"
        else -> "Wi-Fi"
    }
