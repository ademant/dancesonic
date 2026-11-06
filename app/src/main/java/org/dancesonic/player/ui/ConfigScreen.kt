package org.dancesonic.player.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import okhttp3.HttpUrl
import org.dancesonic.player.BuildConfig
import org.dancesonic.player.account.ConnectionUri
import org.dancesonic.player.playback.TempoMode
import org.dancesonic.player.subsonic.ServerSettings

private const val MASKED_KEY = "••••••••"
private const val QR_PIXELS = 768
private val QR_SIZE = 240.dp

/**
 * App version, the stored connection and the DanceBrainz server. The API key stays masked until the user
 * reveals it. A connection can be re-entered by hand or by scanning; both are checked with the server
 * before they replace the stored one. The QR code carries the DanceBrainz server as well.
 */
@Composable
fun ConfigScreen(
    vm: ConnectViewModel,
    danceVm: DanceBrainzViewModel,
    playbackVm: PlaybackSettingsViewModel,
    offlineVm: OfflineViewModel,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    val settings by vm.settings.collectAsStateWithLifecycle()
    val state by vm.state.collectAsStateWithLifecycle()
    val danceUrl by danceVm.baseUrl.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var revealKey by rememberSaveable { mutableStateOf(false) }
    // Not saved: the QR code leaves the screen with the screen, so a rotation or restore hides it again.
    var showQr by remember { mutableStateOf(false) }
    var address by rememberSaveable { mutableStateOf(vm.settings.value?.baseUrl?.toString() ?: "https://") }
    var apiKey by rememberSaveable { mutableStateOf("") }

    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("Back") }
            Text("Config", style = MaterialTheme.typography.headlineSmall)
        }

        Text("App", style = MaterialTheme.typography.titleMedium)
        Text("DanceSonic ${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})")

        Text("Subsonic connection", style = MaterialTheme.typography.titleMedium)
        val current = settings
        if (current == null) {
            Text("Not connected to a server.")
        } else {
            Text("Server: ${current.baseUrl}")
            Text("API key: ${if (revealKey) current.apiKey else MASKED_KEY}")
            TextButton(onClick = { revealKey = !revealKey }) { Text(if (revealKey) "Hide key" else "Show key") }
            OutlinedButton(onClick = { showQr = !showQr }) {
                Text(if (showQr) "Hide QR code" else "Show as QR code")
            }
            if (showQr) {
                Text(
                    "This code contains your API key. Anyone who scans it can use your server. " +
                        "Show it only to a device you own, and hide it afterwards.",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
                ConnectionQr(current, danceUrl)
            }
            Text("Library", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { vm.sync() }, enabled = !state.busy) { Text("Refresh") }
                OutlinedButton(onClick = { vm.sync(full = true) }, enabled = !state.busy) { Text("Full rebuild") }
            }
            TextButton(onClick = { vm.disconnect() }) { Text("Disconnect") }
            if (state.busy) {
                val progress = state.syncProgress
                Text(
                    if (progress == null || progress.total == 0) {
                        "Syncing…"
                    } else {
                        "Syncing ${progress.done} of ${progress.total} albums…"
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        Text("Re-enter connection", style = MaterialTheme.typography.titleMedium)
        Text(
            "The server is checked before the new connection replaces the stored one. " +
                "A different server address clears the synced library; playlists stay.",
            style = MaterialTheme.typography.bodySmall,
        )
        Button(
            onClick = { scope.launch { vm.scanAndConnect(context) } },
            enabled = !state.busy,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Scan code") }
        ServerAddressField(address) { address = it }
        OutlinedTextField(
            value = apiKey,
            onValueChange = { apiKey = it },
            label = { Text("API key") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedButton(
            onClick = { vm.connectWithApiKey(address, apiKey) },
            enabled = !state.busy,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Connect with this key") }

        state.lastSync?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (state.busy) {
            CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
        }

        PlaybackSection(playbackVm)

        OfflineSection(offlineVm)

        DanceBrainzSection(danceVm)
    }
}

/** ReplayGain and the tempo control. Each choice is saved and applied to the player at once. */
@Composable
private fun PlaybackSection(playbackVm: PlaybackSettingsViewModel) {
    val settings by playbackVm.settings.collectAsStateWithLifecycle()
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Playback", style = MaterialTheme.typography.titleMedium)
        Row(
            Modifier.fillMaxWidth().clickable { playbackVm.setReplayGain(!settings.replayGain) },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = settings.replayGain, onCheckedChange = null)
            Text("Use ReplayGain when the track has it")
        }
        Text(
            "ReplayGain lowers loud tracks to a common level. It never raises quiet ones, " +
                "and the phone volume stays under your control.",
            style = MaterialTheme.typography.bodySmall,
        )
        Text("Tempo control", style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = settings.tempoMode == TempoMode.Multiplier,
                onClick = { playbackVm.setTempoMode(TempoMode.Multiplier) },
                label = { Text("Multiplier") },
            )
            FilterChip(
                selected = settings.tempoMode == TempoMode.Bpm,
                onClick = { playbackVm.setTempoMode(TempoMode.Bpm) },
                label = { Text("BPM") },
            )
        }
        Text(
            if (settings.tempoMode == TempoMode.Bpm) {
                "The player bar sets a target BPM. A track with a known tempo plays at that BPM; " +
                    "a track without one plays at the last multiplier."
            } else {
                "The player bar sets a multiplier. Every track plays at that speed."
            },
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

/** The DanceBrainz server: its address is checked, then saved, and the dance data is refreshed from it. */
@Composable
private fun DanceBrainzSection(danceVm: DanceBrainzViewModel) {
    val url by danceVm.baseUrl.collectAsStateWithLifecycle()
    val state by danceVm.state.collectAsStateWithLifecycle()
    val signedIn by danceVm.signedIn.collectAsStateWithLifecycle()
    var address by rememberSaveable { mutableStateOf(danceVm.baseUrl.value.toString()) }
    var token by rememberSaveable { mutableStateOf("") }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("DanceBrainz", style = MaterialTheme.typography.titleMedium)
        Text("Server: $url")
        Text(
            "Dance types and tempo are looked up anonymously. No key is sent to DanceBrainz. " +
                "Saving a different server replaces the dance data with the new server's.",
            style = MaterialTheme.typography.bodySmall,
        )
        ServerAddressField(address) { address = it }
        Button(
            onClick = { danceVm.save(address) },
            enabled = !state.busy,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Save DanceBrainz server") }
        OutlinedButton(
            onClick = { danceVm.useDefault() },
            enabled = !state.busy,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Use the public server") }
        Text("Account", style = MaterialTheme.typography.titleSmall)
        if (signedIn) {
            Text(
                "Signed in. Edits you make are sent with this phone's token.",
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedButton(
                onClick = { danceVm.signOut() },
                enabled = !state.busy,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Sign out of DanceBrainz") }
        } else {
            Text(
                "Sign in to edit dances and shoes ratings. Scan the pairing code from the DanceBrainz account page, " +
                    "or paste a token from it.",
                style = MaterialTheme.typography.bodySmall,
            )
            Button(
                onClick = {
                    scope.launch { danceVm.scanAndSignIn(context) }
                },
                enabled = !state.busy,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Scan pairing code") }
            OutlinedTextField(
                value = token,
                onValueChange = { token = it },
                label = { Text("Token (dbz_…)") },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedButton(
                onClick = { danceVm.signInWithToken(token) },
                enabled = !state.busy && token.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Sign in with token") }
        }
        state.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (state.busy) {
            CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
        }
    }
}

/** The connection as a QR code, rendered in memory for as long as it is composed. */
@Composable
private fun ConnectionQr(
    settings: ServerSettings,
    danceUrl: HttpUrl,
) {
    val bitmap =
        remember(settings, danceUrl) {
            QrCodes.bitmap(ConnectionUri.build(settings, danceUrl), QR_PIXELS).asImageBitmap()
        }
    Image(
        bitmap = bitmap,
        contentDescription = "QR code with the server address, API key and DanceBrainz address",
        modifier = Modifier.size(QR_SIZE),
    )
}
