package org.dancesonic.player.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.dancesonic.player.AppContainer
import org.dancesonic.player.playback.PlaybackSettings
import org.dancesonic.player.playback.TempoMode

/** The playback choices on the config page and the player bar. Each change is saved and applied at once. */
class PlaybackSettingsViewModel(private val container: AppContainer) : ViewModel() {
    val settings: StateFlow<PlaybackSettings> =
        container.playbackSettingsStore.settings
            .stateIn(viewModelScope, SharingStarted.Eagerly, PlaybackSettings())

    fun setReplayGain(enabled: Boolean) = update { it.copy(replayGain = enabled) }

    fun setTempoMode(mode: TempoMode) = update { it.copy(tempoMode = mode) }

    fun setMultiplier(multiplier: Float) = update { it.copy(multiplier = multiplier) }

    fun setTargetBpm(bpm: Int) = update { it.copy(targetBpm = bpm) }

    fun setKeepTempoForNextTrack(keep: Boolean) = update { it.copy(keepTempoForNextTrack = keep) }

    private fun update(change: (PlaybackSettings) -> PlaybackSettings) {
        viewModelScope.launch {
            container.playbackSettingsStore.save(change(container.currentPlaybackSettings))
        }
    }
}
