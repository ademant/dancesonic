package org.dancesonic.player.ui

import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import org.dancesonic.player.studio.PlayOnTarget
import org.dancesonic.player.studio.RelayStudio
import org.dancesonic.player.studio.StudioPairing
import org.dancesonic.player.studio.ble.BlePermissions
import org.dancesonic.player.subsonic.LocalNetworkAccess

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
    val lastActive by vm.lastActive.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pending by remember { mutableStateOf<(() -> Unit)?>(null) }
    val permissions =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            // Denied permissions are fine: without Bluetooth the link uses the network or the relay, and without the
            // local network the error says so.
            pending?.invoke()
            pending = null
        }
    // Runs [action] once Bluetooth and the local network may be used, asking for the permissions first when they are
    // missing. Both are "Nearby devices" on Android 17, so they share one prompt (#124).
    val withBluetooth: (() -> Unit) -> Unit = { action ->
        if (BlePermissions.granted(context) && LocalNetworkAccess.granted(context)) {
            action()
        } else {
            pending = action
            permissions.launch(BlePermissions.required() + LocalNetworkAccess.required())
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
            studioTarget?.let { "${it.name} (${transportLabel(it.transport)})" } ?: "this phone"
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
        PairingList(pairings, studioTarget?.pairingId, lastActive, state.busy, vm, withBluetooth)
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
        FolksonicPairing(
            state = state,
            onFind = { vm.findOnFolksonic() },
            onPair = { studio -> vm.pairThroughFolksonic(studio, Build.MODEL ?: "Phone") },
        )
        state.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (state.busy) {
            CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
        }
    }
}

/** The paired Studios, each with its role: playing now, last played on, or neither. */
@Suppress("LongParameterList") // the list's state and the view model's actions, passed straight to each row
@Composable
private fun PairingList(
    pairings: List<StudioPairing>,
    playingId: String?,
    lastActive: String?,
    busy: Boolean,
    vm: StudioViewModel,
    withBluetooth: (() -> Unit) -> Unit,
) {
    pairings.forEach { record ->
        val role =
            when (record.id) {
                playingId -> StudioRowRole.Playing
                lastActive -> StudioRowRole.LastUsed
                else -> StudioRowRole.Other
            }
        StudioPairingRow(
            record = record,
            role = role,
            busy = busy,
            actions =
                StudioRowActions(
                    onPlayOn = { withBluetooth { vm.playOn(record) } },
                    onCheck = { withBluetooth { vm.check(record) } },
                    onRename = { name -> vm.rename(record, name) },
                    onForget = { vm.forget(record) },
                ),
        )
    }
}

/**
 * Pairing through folksonic (#104): lists the account's Studios, and while one is pairing shows the code to compare
 * with Studio's screen. A server in the middle would show a different code on each side.
 */
@Composable
private fun FolksonicPairing(
    state: StudioUiState,
    onFind: () -> Unit,
    onPair: (RelayStudio) -> Unit,
) {
    OutlinedButton(onClick = onFind, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
        Text("Pair through folksonic")
    }
    state.relayStudios?.forEach { studio ->
        TextButton(onClick = { onPair(studio) }, enabled = !state.busy) { Text("Pair with ${studio.name}") }
    }
    state.comparisonCode?.let { code ->
        Text(
            code.chunked(CODE_GROUP).joinToString(" "),
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.semantics { contentDescription = "Comparison code $code" },
        )
    }
}

private const val CODE_GROUP = 3
