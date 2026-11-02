package org.dancesonic.player.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
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
            when (tab) {
                Tab.Library -> LibraryScreen(libraryVm, container.player, playlistVm, connectVm)
                Tab.Dance -> DanceScreen(libraryVm, container.player, playlistVm)
                Tab.Playlists -> PlaylistsScreen(playlistVm)
            }
        }
    }
}

@Composable
fun ConnectScreen(vm: ConnectViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    var address by rememberSaveable { mutableStateOf("https://") }
    var apiKey by rememberSaveable { mutableStateOf("") }

    Column(
        Modifier.fillMaxWidth().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Connect to Navidrome", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Create an API key in Navidrome (user settings) and paste it here. " +
                "Only https:// addresses are accepted. Your password is never stored.",
            style = MaterialTheme.typography.bodyMedium,
        )
        OutlinedTextField(
            value = address,
            onValueChange = { address = it },
            label = { Text("Server address") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = apiKey,
            onValueChange = { apiKey = it },
            label = { Text("API key") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
        )
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (state.busy) {
            CircularProgressIndicator()
        } else {
            Button(
                onClick = { vm.connect(address, apiKey) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Connect") }
        }
    }
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
