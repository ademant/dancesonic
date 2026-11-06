package org.dancesonic.player.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import org.dancesonic.player.data.KeptCollections
import org.dancesonic.player.playback.DownloadBlock
import org.dancesonic.player.playback.OfflineStatus

/** A size in bytes in KB, MB or GB, using decimal units as storage is sold. */
internal fun formatBytes(bytes: Long): String =
    when {
        bytes >= GIGA -> "%.1f GB".format(bytes / GIGA.toDouble())
        bytes >= MEGA -> "%.1f MB".format(bytes / MEGA.toDouble())
        bytes >= KILO -> "%.0f KB".format(bytes / KILO.toDouble())
        else -> "$bytes B"
    }

/** The lines that describe the offline state: the cache in use, what is kept, and why downloads are paused. */
internal fun offlineLines(status: OfflineStatus): List<String> =
    buildList {
        add("Audio cache: ${formatBytes(status.cacheBytes)} in use, kept songs included.")
        if (status.keptTracks + status.unsizedTracks == 0) {
            add("Nothing is kept offline.")
        } else {
            add(
                "Kept: ${status.heldTracks} of ${status.keptTracks} tracks on the phone " +
                    "(${formatBytes(status.heldBytes)}).",
            )
        }
        if (status.unsizedTracks > 0) {
            add("${status.unsizedTracks} kept tracks have no size from the server. Run Full rebuild to download them.")
        }
        status.blocked?.let { add(blockedText(it)) }
    }

private fun blockedText(block: DownloadBlock): String =
    when (block) {
        DownloadBlock.Offline -> "Downloads are paused: the phone is offline. They resume when it is back online."
        DownloadBlock.WaitingForWifi ->
            "Downloads wait for Wi-Fi. Allow mobile data below to download on mobile networks."
    }

/** Marks an album or playlist to stay on the phone, or unmarks it. Unmarking stops the downloads only. */
@Composable
internal fun KeepOfflineButton(
    kept: Boolean,
    onChange: (Boolean) -> Unit,
) {
    OutlinedButton(onClick = { onChange(!kept) }) {
        Text(if (kept) "Stop keeping offline" else "Keep offline")
    }
}

/** The keep button for one album or playlist, reading and saving its choice. */
@Composable
internal fun KeepOfflineToggle(
    kind: String,
    refId: String,
    keptCollections: KeptCollections,
) {
    val scope = rememberCoroutineScope()
    val kept by remember(kind, refId) { keptCollections.observe(kind, refId) }.collectAsStateWithLifecycle(false)
    KeepOfflineButton(kept = kept) { keep -> scope.launch { keptCollections.set(kind, refId, keep) } }
}

/** The offline section of the config page. */
@Composable
internal fun OfflineSection(offlineVm: OfflineViewModel) {
    val status by offlineVm.status.collectAsStateWithLifecycle()
    val settings by offlineVm.settings.collectAsStateWithLifecycle()
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Offline", style = MaterialTheme.typography.titleMedium)
        Text(
            "Albums and playlists marked Keep offline are downloaded to the phone and stay there until unmarked.",
            style = MaterialTheme.typography.bodySmall,
        )
        offlineLines(status).forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
        Row(
            Modifier.fillMaxWidth().clickable { offlineVm.setAllowMobileData(!settings.allowMobileData) },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Download over mobile data", Modifier.weight(1f))
            Switch(checked = settings.allowMobileData, onCheckedChange = null)
        }
    }
}

private const val KILO = 1_000L
private const val MEGA = 1_000_000L
private const val GIGA = 1_000_000_000L
