package org.dancesonic.player.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SliderState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import org.dancesonic.player.AppContainer
import org.dancesonic.player.playback.PlaybackMath
import org.dancesonic.player.playback.PlayerController
import org.dancesonic.player.playback.TempoMode
import kotlin.math.roundToInt

private const val TEMPO_STEPS = 19

/**
 * The bottom tabs: a compact row, [TAB_ROW_HEIGHT] high, with each label centred. The system navigation bar's inset is
 * added below the row, so no tab is drawn under the gesture bar.
 */
@Composable
private fun TabRow(
    selected: Tab,
    onSelect: (Tab) -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Column {
            Row(Modifier.fillMaxWidth().height(TAB_ROW_HEIGHT).selectableGroup()) {
                Tab.entries.forEach { entry ->
                    val isSelected = entry == selected
                    Box(
                        Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .padding(horizontal = 8.dp, vertical = 8.dp)
                            .clip(RoundedCornerShape(TAB_CORNER))
                            .background(
                                if (isSelected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
                            )
                            .selectable(selected = isSelected, role = Role.Tab, onClick = { onSelect(entry) }),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            entry.label,
                            style = MaterialTheme.typography.labelLarge,
                            color =
                                if (isSelected) {
                                    MaterialTheme.colorScheme.onSecondaryContainer
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                        )
                    }
                }
            }
            Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
        }
    }
}

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
    var showNowPlaying by rememberSaveable { mutableStateOf(false) }
    val libraryVm: LibraryViewModel = viewModel { LibraryViewModel(container) }
    val playlistVm: PlaylistViewModel = viewModel { PlaylistViewModel(container) }
    val danceVm: DanceBrainzViewModel = viewModel { DanceBrainzViewModel(container) }
    val playbackVm: PlaybackSettingsViewModel = viewModel { PlaybackSettingsViewModel(container) }
    val offlineVm: OfflineViewModel = viewModel { OfflineViewModel(container) }

    Scaffold(
        bottomBar = {
            Column {
                PlayerBar(container.player, playbackVm, libraryVm, onExpand = { showNowPlaying = true })
                HorizontalDivider()
                TabRow(selected = tab, onSelect = { tab = it })
            }
        },
    ) { padding ->
        Surface(Modifier.padding(padding).fillMaxWidth()) {
            if (showConfig) {
                ConfigScreen(connectVm, danceVm, playbackVm, offlineVm, onBack = { showConfig = false })
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
    if (showNowPlaying) {
        NowPlayingSheet(libraryVm, container.player) { showNowPlaying = false }
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
    // Secrets are plain remember, not saved: they never reach the saved-instance state, and they are cleared
    // after each attempt. Leaving the screen drops them too.
    var password by remember { mutableStateOf("") }
    var apiKey by remember { mutableStateOf("") }

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
                    onClick = {
                        vm.signInWithPassword(address, username, password)
                        password = ""
                    },
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
                    onClick = {
                        vm.connectWithApiKey(address, apiKey)
                        apiKey = ""
                    },
                    enabled = !state.busy,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Connect") }
            }
        }

        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

        state.status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerBar(
    player: PlayerController,
    playbackVm: PlaybackSettingsViewModel,
    libraryVm: LibraryViewModel,
    onExpand: () -> Unit,
) {
    val state by player.state.collectAsStateWithLifecycle()
    val settings by playbackVm.settings.collectAsStateWithLifecycle()
    val bpmMode = settings.tempoMode == TempoMode.Bpm
    var tempo by remember(settings.multiplier) { mutableFloatStateOf(settings.multiplier) }
    var targetBpm by remember(settings.targetBpm) { mutableFloatStateOf(settings.targetBpm.toFloat()) }

    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(
            text = state.nowPlayingTitle ?: "Nothing playing",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.clickable(onClick = onExpand),
        )
        state.nowPlayingArtist?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        SeekBar(player, libraryVm.nowPlayingWaveform)
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = { player.previous() }) { Text("Prev") }
            Spacer(Modifier.width(8.dp))
            Button(onClick = { player.togglePlayPause() }) { Text(if (state.isPlaying) "Pause" else "Play") }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = { player.next() }) { Text("Next") }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            val label =
                if (bpmMode) {
                    "Tempo ${targetBpm.roundToInt()} BPM"
                } else {
                    "Tempo ${"%.2f".format(tempo)}x"
                }
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                // A tap on the label switches between BPM and multiplier.
                modifier =
                    Modifier.clickable {
                        playbackVm.setTempoMode(if (bpmMode) TempoMode.Multiplier else TempoMode.Bpm)
                    },
            )
            val markerFraction = bpmMarkerFraction(bpmMode, state.trackBpm)
            Slider(
                value = if (bpmMode) targetBpm else tempo,
                onValueChange = { if (bpmMode) targetBpm = it else tempo = it },
                onValueChangeFinished = {
                    if (bpmMode) {
                        playbackVm.setTargetBpm(targetBpm.roundToInt())
                    } else {
                        playbackVm.setMultiplier(tempo)
                    }
                },
                valueRange =
                    if (bpmMode) {
                        PlaybackMath.MIN_TARGET_BPM.toFloat()..PlaybackMath.MAX_TARGET_BPM.toFloat()
                    } else {
                        PlaybackMath.MIN_TEMPO..PlaybackMath.MAX_TEMPO
                    },
                steps = if (bpmMode) PlaybackMath.MAX_TARGET_BPM - PlaybackMath.MIN_TARGET_BPM - 1 else TEMPO_STEPS,
                track = { sliderState -> TrackWithMarker(sliderState, markerFraction) },
                modifier = Modifier.weight(1f).padding(start = 8.dp),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = settings.keepTempoForNextTrack,
                    onCheckedChange = { playbackVm.setKeepTempoForNextTrack(it) },
                )
                Text("Keep", style = MaterialTheme.typography.labelSmall)
            }
            ResetTempoButton(playbackVm, bpmMode, state.trackBpm)
        }
    }
}

/**
 * The slider track, with a thin marker at [markerFraction] (0 to 1 across the track) when there is one. The marker
 * shows the recording's own BPM, so the listener can see how far the target is from it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TrackWithMarker(
    sliderState: SliderState,
    markerFraction: Float?,
) {
    Box(Modifier.fillMaxWidth()) {
        SliderDefaults.Track(sliderState = sliderState, modifier = Modifier.fillMaxWidth())
        if (markerFraction != null) {
            val markerColor = MaterialTheme.colorScheme.tertiary
            Canvas(Modifier.matchParentSize()) {
                val x = size.width * markerFraction
                drawLine(
                    color = markerColor,
                    start = Offset(x, 0f),
                    end = Offset(x, size.height),
                    strokeWidth = MARKER_WIDTH.toPx(),
                )
            }
        }
    }
}

private val MARKER_WIDTH = 3.dp

/** Where the track's own BPM sits on the BPM slider, from 0 to 1, or null when the slider shows no BPM. */
private fun bpmMarkerFraction(
    bpmMode: Boolean,
    trackBpm: Int?,
): Float? {
    if (!bpmMode || trackBpm == null) return null
    val range = PlaybackMath.MAX_TARGET_BPM - PlaybackMath.MIN_TARGET_BPM
    return (trackBpm - PlaybackMath.MIN_TARGET_BPM).toFloat() / range
}

/**
 * Puts the tempo back to the original: 1x in multiplier mode, and in BPM mode the track's own BPM, so it plays at its
 * original speed. Disabled in BPM mode when the track has no BPM.
 */
@Composable
private fun ResetTempoButton(
    playbackVm: PlaybackSettingsViewModel,
    bpmMode: Boolean,
    trackBpm: Int?,
) {
    TextButton(
        onClick = {
            if (bpmMode) {
                playbackVm.setTargetBpm(trackBpm ?: PlaybackMath.DEFAULT_TARGET_BPM)
            } else {
                playbackVm.setMultiplier(1f)
            }
        },
        enabled = !bpmMode || trackBpm != null,
    ) { Text("Reset") }
}

private val TAB_ROW_HEIGHT = 64.dp
private val TAB_CORNER = 16.dp
