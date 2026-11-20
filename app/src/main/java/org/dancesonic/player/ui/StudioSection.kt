package org.dancesonic.player.ui

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import org.dancesonic.player.studio.StudioPairing

/**
 * The paired Studios: pair a new one from its QR code, check that each answers, and forget one. Studio must accept the
 * phone before it is listed. Forgetting here removes the phone's key only; Studio keeps it listed until it is
 * revoked there.
 */
@Composable
internal fun StudioSection(vm: StudioViewModel) {
    val pairings by vm.pairings.collectAsStateWithLifecycle()
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            "Play on a DanceSonic Studio on this network. Open \"Pair a phone\" in Studio, then scan its code here, " +
                "and accept this phone in Studio.",
            style = MaterialTheme.typography.bodySmall,
        )
        if (pairings.isEmpty()) {
            Text("No Studio is paired.", style = MaterialTheme.typography.bodySmall)
        }
        pairings.forEach { record -> StudioRow(record, vm, busy = state.busy) }
        Button(
            onClick = {
                scope.launch {
                    val code = scanQrCode(context.applicationContext)
                    if (code != null) vm.pair(code, Build.MODEL ?: "Phone")
                }
            },
            enabled = !state.busy,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Pair a Studio (scan code)") }
        state.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (state.busy) {
            CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
        }
    }
}

@Composable
private fun StudioRow(
    record: StudioPairing,
    vm: StudioViewModel,
    busy: Boolean,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(record.name, style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { vm.check(record) }, enabled = !busy) { Text("Check") }
            TextButton(onClick = { vm.forget(record) }, enabled = !busy) { Text("Forget") }
        }
    }
}
