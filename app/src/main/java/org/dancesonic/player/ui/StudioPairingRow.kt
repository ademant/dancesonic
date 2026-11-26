package org.dancesonic.player.ui

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import org.dancesonic.player.studio.StudioPairing

/** What a pairing row can do. */
internal class StudioRowActions(
    val onPlayOn: () -> Unit,
    val onCheck: () -> Unit,
    val onRename: (String) -> Unit,
    val onForget: () -> Unit,
)

/** How a pairing row shows its Studio's state relative to "Play on". */
internal enum class StudioRowRole {
    /** Playback runs on this Studio now. */
    Playing,

    /** The Studio last chosen with "Play on"; the phone is not connected to it now. */
    LastUsed,

    Other,
}

/** The transports a pairing can use, as the row lists them: "Bluetooth, folksonic relay, Wi-Fi". */
internal fun transportsText(record: StudioPairing): String =
    record.transports().joinToString(", ") { transportLabel(it) }

/** When and how the phone last reached the Studio, or that it has not yet. [nowMs] is the current time. */
internal fun lastConnectedText(
    record: StudioPairing,
    nowMs: Long,
): String {
    val at = record.lastConnectedAtMs ?: return "Not connected yet"
    val ago = DateUtils.getRelativeTimeSpanString(at, nowMs, DateUtils.MINUTE_IN_MILLIS)
    val via = record.lastTransport?.let { " over ${transportLabel(it)}" }.orEmpty()
    return "Last connected $ago$via"
}

/**
 * One paired Studio (#88): its name, the transports it offers and when it was last connected, with Play on, Check,
 * Rename and Forget.
 */
@Composable
internal fun StudioPairingRow(
    record: StudioPairing,
    role: StudioRowRole,
    busy: Boolean,
    actions: StudioRowActions,
    nowMs: Long = System.currentTimeMillis(),
) {
    var renaming by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        val title =
            when (role) {
                StudioRowRole.Playing -> "${record.name} (playing)"
                StudioRowRole.LastUsed -> "${record.name} (last played on)"
                StudioRowRole.Other -> record.name
            }
        Text(title, style = MaterialTheme.typography.titleSmall)
        Text(
            "${transportsText(record)} · ${lastConnectedText(record, nowMs)}",
            style = MaterialTheme.typography.bodySmall,
        )
        if (record.phoneNoiseSealed == null) {
            Text(
                "Paired before Bluetooth and folksonic were supported: forget it and pair again to use them.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = actions.onPlayOn, enabled = !busy && role != StudioRowRole.Playing) { Text("Play on") }
            OutlinedButton(onClick = actions.onCheck, enabled = !busy) { Text("Check") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { renaming = true }, enabled = !busy) { Text("Rename") }
            TextButton(onClick = actions.onForget, enabled = !busy) { Text("Forget") }
        }
    }
    if (renaming) {
        RenameStudioDialog(
            current = record.name,
            onDone = { name ->
                renaming = false
                name?.let(actions.onRename)
            },
        )
    }
}

/** Asks for a new name. [onDone] gets the name, or null when the dialog was cancelled. */
@Composable
private fun RenameStudioDialog(
    current: String,
    onDone: (String?) -> Unit,
) {
    var text by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = { onDone(null) },
        title = { Text("Rename Studio") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.take(MAX_STUDIO_NAME) },
                singleLine = true,
                label = { Text("Name") },
            )
        },
        confirmButton = {
            TextButton(onClick = { onDone(text) }, enabled = studioName(text) != null) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = { onDone(null) }) { Text("Cancel") } },
    )
}
