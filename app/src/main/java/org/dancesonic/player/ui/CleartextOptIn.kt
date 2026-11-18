package org.dancesonic.player.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

/** The warning shown before plain http is allowed: what is exposed, to whom, and what to do instead. */
internal const val CLEARTEXT_WARNING =
    "Plain http does not encrypt anything. Anyone who can see your network traffic, on the same Wi-Fi or on " +
        "any device in between, can read and change the requests, and capture your API key or device pairing, " +
        "your library and what you play. Use it only for a server on a home network you trust. " +
        "Use https whenever you can."

internal const val CLEARTEXT_OPTION = "Allow plain http for a server on my local network"
internal const val CLEARTEXT_CONFIRM = "I understand, allow http"

/**
 * The http opt-in (#84). Off by default. Turning it on opens a warning that must be acknowledged; turning it off needs
 * no confirmation. Only private network addresses can use http even when it is on.
 */
@Composable
internal fun CleartextOptIn(
    allowed: Boolean,
    onChange: (Boolean) -> Unit,
) {
    var asking by rememberSaveable { mutableStateOf(false) }
    Column {
        Row(
            Modifier.fillMaxWidth().clickable { if (allowed) onChange(false) else asking = true },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = allowed, onCheckedChange = null)
            Text(CLEARTEXT_OPTION)
        }
        if (allowed) {
            Text(
                "Plain http is allowed for a private network address. Your key is not encrypted on that network.",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
    if (asking) {
        AlertDialog(
            onDismissRequest = { asking = false },
            title = { Text("Allow unencrypted http?") },
            text = { Text(CLEARTEXT_WARNING) },
            confirmButton = {
                TextButton(onClick = {
                    asking = false
                    onChange(true)
                }) { Text(CLEARTEXT_CONFIRM) }
            },
            dismissButton = { TextButton(onClick = { asking = false }) { Text("Cancel") } },
        )
    }
}
