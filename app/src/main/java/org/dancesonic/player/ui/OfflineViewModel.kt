package org.dancesonic.player.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.dancesonic.player.AppContainer
import org.dancesonic.player.playback.DownloadSettings
import org.dancesonic.player.playback.OfflineStatus

/** The offline section of the config page: what is kept and downloaded, and whether mobile data may be used. */
class OfflineViewModel(private val container: AppContainer) : ViewModel() {
    val status: StateFlow<OfflineStatus> =
        container.offline.status
            .stateIn(viewModelScope, SharingStarted.Eagerly, OfflineStatus())

    val settings: StateFlow<DownloadSettings> =
        container.downloadSettingsStore.settings
            .stateIn(viewModelScope, SharingStarted.Eagerly, DownloadSettings())

    fun setAllowMobileData(allow: Boolean) {
        viewModelScope.launch { container.downloadSettingsStore.save(DownloadSettings(allowMobileData = allow)) }
    }
}
