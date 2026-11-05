package org.dancesonic.player.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
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
import androidx.compose.material3.CircularProgressIndicator
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
import org.dancesonic.player.BuildConfig
import org.dancesonic.player.account.ConnectionUri
import org.dancesonic.player.dance.DanceBrainzClient
import org.dancesonic.player.subsonic.ServerSettings

private const val MASKED_KEY = "••••••••"
private const val QR_PIXELS = 768
private val QR_SIZE = 240.dp

/**
 * App version, the stored connection and DanceBrainz settings. The API key stays masked until the user
 * reveals it. A connection can be re-entered by hand or by scanning; both are checked with the server
 * before they replace the stored one.
 */
@Composable
fun ConfigScreen(
    vm: ConnectViewModel,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    val settings by vm.settings.collectAsStateWithLifecycle()
    val state by vm.state.collectAsStateWithLifecycle()
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
                ConnectionQr(current)
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

        Text("DanceBrainz", style = MaterialTheme.typography.titleMedium)
        Text("Server: ${DanceBrainzClient.DEFAULT_BASE_URL}")
        Text(
            "Dance types and tempo are looked up anonymously. No key is sent to DanceBrainz.",
            style = MaterialTheme.typography.bodySmall,
        )

        state.lastSync?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (state.busy) {
            CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
        }
    }
}

/** The connection as a QR code, rendered in memory for as long as it is composed. */
@Composable
private fun ConnectionQr(settings: ServerSettings) {
    val bitmap = remember(settings) { QrCodes.bitmap(ConnectionUri.build(settings), QR_PIXELS).asImageBitmap() }
    Image(
        bitmap = bitmap,
        contentDescription = "QR code with the server address and API key",
        modifier = Modifier.size(QR_SIZE),
    )
}
