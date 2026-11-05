package org.dancesonic.player.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import org.dancesonic.player.AppContainer
import org.dancesonic.player.playback.PlayerController

private enum class Tab(val label: String) {
    Library("Library"),
    Dance("Dance"),
    Playlists("Playlists"),
}

@Composable
fun DanceSonicRoot(container: AppContainer) {
    val connectVm: ConnectViewModel = viewModel { ConnectViewModel(container) }
    val connected by connectVm.connected.collectAsStateWithLifecycle()

    if (!connected) {
        ConnectScreen(connectVm)
        return
    }

    var tab by rememberSaveable { mutableStateOf(Tab.Library) }
    var showConfig by rememberSaveable { mutableStateOf(false) }
    val libraryVm: LibraryViewModel = viewModel { LibraryViewModel(container) }
    val playlistVm: PlaylistViewModel = viewModel { PlaylistViewModel(container) }

    Scaffold(
        bottomBar = {
            Column {
                PlayerBar(container.player)
                HorizontalDivider()
                NavigationBar {
                    Tab.entries.forEach { entry ->
                        NavigationBarItem(
                            selected = tab == entry,
                            onClick = { tab = entry },
                            icon = { Text(entry.label.take(1)) },
                            label = { Text(entry.label) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        Surface(Modifier.padding(padding).fillMaxWidth()) {
            if (showConfig) {
                ConfigScreen(connectVm, onBack = { showConfig = false })
            } else {
                when (tab) {
                    Tab.Library ->
                        LibraryScreen(
                            libraryVm,
                            container.player,
                            playlistVm,
                            connectVm,
                            onOpenConfig = { showConfig = true },
                        )
                    Tab.Dance -> DanceScreen(libraryVm, container.player, playlistVm)
                    Tab.Playlists -> PlaylistsScreen(playlistVm)
                }
            }
        }
    }
}

private enum class ConnectMethod(val label: String) {
    Qr("QR code"),
    Password("Username & password"),
    ApiKey("API key"),
}

@Composable
fun ConnectScreen(vm: ConnectViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var method by rememberSaveable { mutableStateOf(ConnectMethod.Qr) }
    var address by rememberSaveable { mutableStateOf("https://") }
    var username by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var apiKey by rememberSaveable { mutableStateOf("") }

    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Connect to your server", style = MaterialTheme.typography.headlineSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ConnectMethod.entries.forEach { entry ->
                FilterChip(
                    selected = method == entry,
                    onClick = { method = entry },
                    label = { Text(entry.label) },
                )
            }
        }

        when (method) {
            ConnectMethod.Qr -> {
                Text(
                    "On the server's account page, create a pairing code and scan it here. " +
                        "The code works once and expires after five minutes.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Button(
                    onClick = { scope.launch { vm.scanAndConnect(context) } },
                    enabled = !state.busy,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Scan pairing code") }
            }

            ConnectMethod.Password -> {
                Text(
                    "Sign in with your server account. The app stores only an API key, never your password.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                ServerAddressField(address) { address = it }
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text("Username") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Password") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    onClick = { vm.signInWithPassword(address, username, password) },
                    enabled = !state.busy,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Sign in") }
            }

            ConnectMethod.ApiKey -> {
                Text(
                    "Paste an API key created on the server. Only https:// addresses are accepted.",
                    style = MaterialTheme.typography.bodyMedium,
                )
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
                Button(
                    onClick = { vm.connectWithApiKey(address, apiKey) },
                    enabled = !state.busy,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Connect") }
            }
        }

        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (state.busy) {
            CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
        }
    }
}

@Composable
internal fun ServerAddressField(
    value: String,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text("Server address") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
fun PlayerBar(player: PlayerController) {
    val state by player.state.collectAsStateWithLifecycle()
    var tempo by remember(state.tempo) { mutableFloatStateOf(state.tempo) }

    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(
            text = state.nowPlayingTitle ?: "Nothing playing",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        state.nowPlayingArtist?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = { player.previous() }) { Text("Prev") }
            Spacer(Modifier.width(8.dp))
            Button(onClick = { player.togglePlayPause() }) { Text(if (state.isPlaying) "Pause" else "Play") }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = { player.next() }) { Text("Next") }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Tempo ${"%.2f".format(tempo)}x", style = MaterialTheme.typography.labelMedium)
            Slider(
                value = tempo,
                onValueChange = { tempo = it },
                onValueChangeFinished = { player.setTempo(tempo) },
                valueRange = PlayerController.MIN_TEMPO..PlayerController.MAX_TEMPO,
                steps = 19,
                modifier = Modifier.weight(1f).padding(start = 8.dp),
            )
        }
    }
}
