package org.dancesonic.player.ui

import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import org.dancesonic.player.studio.PlayOnTarget
import org.dancesonic.player.studio.StudioPairing
import org.dancesonic.player.studio.ble.BlePermissions

/**
 * The paired Studios: pair a new one from its QR code, play on one, check that each answers, and forget one. Studio
 * must accept the phone before it is listed. Bluetooth is used when it is permitted and Studio is in range; the
 * permissions are asked for before the first scan. Forgetting here removes the phone's key only; Studio keeps it
 * listed until it is revoked there.
 */
@Composable
internal fun StudioSection(vm: StudioViewModel) {
    val pairings by vm.pairings.collectAsStateWithLifecycle()
    val state by vm.state.collectAsStateWithLifecycle()
    val target by vm.target.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pending by remember { mutableStateOf<(() -> Unit)?>(null) }
    val permissions =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            // Denied permissions are fine: without them the link uses the network.
            pending?.invoke()
            pending = null
        }
    // Runs [action] once Bluetooth may be used, asking for the permissions first when they are missing.
    val withBluetooth: (() -> Unit) -> Unit = { action ->
        if (BlePermissions.granted(context)) {
            action()
        } else {
            pending = action
            permissions.launch(BlePermissions.required())
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            "Play on a DanceSonic Studio nearby. Open \"Pair a phone\" in Studio, then scan its code here, " +
                "and accept this phone in Studio.",
            style = MaterialTheme.typography.bodySmall,
        )
        val studioTarget = target as? PlayOnTarget.Target.Studio
        val playingOn =
            studioTarget?.let { "${it.name} (${transportName(it.transport)})" } ?: "this phone"
        Text("Playing on: $playingOn", style = MaterialTheme.typography.titleSmall)
        if (target !is PlayOnTarget.Target.Phone) {
            OutlinedButton(
                onClick = { vm.playOnPhone() },
                enabled = !state.busy,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Play on this phone") }
        }
        if (pairings.isEmpty()) {
            Text("No Studio is paired.", style = MaterialTheme.typography.bodySmall)
        }
        pairings.forEach { record ->
            val active = studioTarget?.pairingId == record.id
            StudioRow(
                record = record,
                busy = state.busy,
                active = active,
                onPlayOn = { withBluetooth { vm.playOn(record) } },
                onCheck = { withBluetooth { vm.check(record) } },
                onForget = { vm.forget(record) },
            )
        }
        Button(
            onClick = {
                withBluetooth {
                    scope.launch {
                        val code = scanQrCode(context.applicationContext)
                        if (code != null) vm.pair(code, Build.MODEL ?: "Phone")
                    }
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

private fun transportName(transport: String): String = if (transport == "ble") "Bluetooth" else "Wi-Fi"

@Suppress("LongParameterList")
@Composable
private fun StudioRow(
    record: StudioPairing,
    busy: Boolean,
    active: Boolean,
    onPlayOn: () -> Unit,
    onCheck: () -> Unit,
    onForget: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(if (active) "${record.name} (playing)" else record.name, style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onPlayOn, enabled = !busy && !active) { Text("Play on") }
            OutlinedButton(onClick = onCheck, enabled = !busy) { Text("Check") }
            TextButton(onClick = onForget, enabled = !busy) { Text("Forget") }
        }
    }
}
