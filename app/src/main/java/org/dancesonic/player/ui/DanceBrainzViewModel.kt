package org.dancesonic.player.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import okhttp3.HttpUrl
import org.dancesonic.player.AppContainer
import org.dancesonic.player.dance.DanceBrainzClient
import org.dancesonic.player.dance.DanceBrainzException
import org.dancesonic.player.subsonic.ServerUrl
import org.dancesonic.player.subsonic.SubsonicException

data class DanceBrainzUiState(
    val busy: Boolean = false,
    val message: String? = null,
    val error: String? = null,
)

/**
 * The DanceBrainz server on the config page. A new address is checked before it is saved. Saving runs an
 * incremental sync at once, so dance data from the new server appears without a separate Refresh.
 */
class DanceBrainzViewModel(private val container: AppContainer) : ViewModel() {
    val baseUrl: StateFlow<HttpUrl> =
        container.danceBrainzSettings.baseUrl
            .stateIn(viewModelScope, SharingStarted.Eagerly, DanceBrainzClient.DEFAULT_BASE_URL)

    private val _state = MutableStateFlow(DanceBrainzUiState())
    val state: StateFlow<DanceBrainzUiState> = _state.asStateFlow()

    /** Checks that [address] is a DanceBrainz server, then saves it and refreshes the dance data. */
    fun save(address: String) {
        val url =
            try {
                ServerUrl.parse(address)
            } catch (e: IllegalArgumentException) {
                _state.value = DanceBrainzUiState(error = e.message)
                return
            }
        _state.value = DanceBrainzUiState(busy = true)
        viewModelScope.launch {
            try {
                DanceBrainzClient(url).checkServer()
                container.danceBrainzSettings.save(url)
                refreshDanceData("Saved.")
            } catch (e: CancellationException) {
                throw e
            } catch (e: DanceBrainzException) {
                _state.value = DanceBrainzUiState(error = e.message)
            }
        }
    }

    /** Goes back to the public DanceBrainz server and refreshes the dance data. */
    fun useDefault() {
        _state.value = DanceBrainzUiState(busy = true)
        viewModelScope.launch {
            container.danceBrainzSettings.resetToDefault()
            refreshDanceData("Using the public DanceBrainz server.")
        }
    }

    /** Runs an incremental sync, which looks up what the current server has for the library. */
    private suspend fun refreshDanceData(prefix: String) {
        val settings = container.currentSettings
        if (settings == null) {
            _state.value = DanceBrainzUiState(message = "$prefix Connect to a server to look up dance data.")
            return
        }
        try {
            val failed = container.library.syncIncremental(container.catalogClient(settings)).danceLookupsFailed
            val note =
                if (failed > 0) {
                    "$failed dance lookups failed; they are retried on the next sync."
                } else {
                    "Dance data is up to date."
                }
            _state.value = DanceBrainzUiState(message = "$prefix $note")
        } catch (e: SubsonicException) {
            _state.value = DanceBrainzUiState(error = "$prefix The library refresh failed: ${e.message}")
        }
    }
}
