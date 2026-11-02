package org.dancesonic.player.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import okhttp3.HttpUrl
import org.dancesonic.player.AppContainer
import org.dancesonic.player.data.db.PlaylistItemRow
import org.dancesonic.player.subsonic.ServerSettings
import org.dancesonic.player.subsonic.ServerUrl
import org.dancesonic.player.subsonic.SubsonicClient
import org.dancesonic.player.subsonic.SubsonicException

data class ConnectUiState(
    val busy: Boolean = false,
    val error: String? = null,
    val lastSync: String? = null,
)

/** Connection setup (URL + API key), catalog sync and disconnect. */
class ConnectViewModel(private val container: AppContainer) : ViewModel() {

    val connected: StateFlow<Boolean> = container.settingsStore.settings
        .map { it != null }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private val _state = MutableStateFlow(ConnectUiState())
    val state: StateFlow<ConnectUiState> = _state.asStateFlow()

    /** Validates the address and key against the server, then stores them. */
    fun connect(address: String, apiKey: String) {
        val key = apiKey.trim()
        val url: HttpUrl = try {
            ServerUrl.parse(address)
        } catch (e: IllegalArgumentException) {
            _state.value = ConnectUiState(error = e.message)
            return
        }
        if (key.isBlank()) {
            _state.value = ConnectUiState(error = "Enter an API key.")
            return
        }
        _state.value = ConnectUiState(busy = true)
        viewModelScope.launch {
            try {
                val settings = ServerSettings(url, key)
                SubsonicClient(settings).checkConnection()
                container.settingsStore.save(settings)
                _state.value = ConnectUiState()
                sync()
            } catch (e: CancellationException) {
                throw e
            } catch (e: SubsonicException) {
                _state.value = ConnectUiState(error = e.message ?: "Connection failed")
            } catch (e: IllegalArgumentException) {
                _state.value = ConnectUiState(error = e.message ?: "Invalid settings")
            }
        }
    }

    /** Refreshes the local catalog and DanceBrainz metadata. Safe to call while playing. */
    fun sync() {
        val settings = container.currentSettings ?: return
        _state.value = _state.value.copy(busy = true, error = null)
        viewModelScope.launch {
            try {
                val result = container.library.sync(SubsonicClient(settings))
                val danceNote = if (result.danceLookupsFailed > 0) {
                    ", ${result.danceLookupsFailed} dance lookups failed"
                } else {
                    ""
                }
                _state.value = ConnectUiState(
                    lastSync = "Synced ${result.artists} artists, ${result.albums} albums, ${result.tracks} tracks$danceNote",
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: SubsonicException) {
                _state.value = ConnectUiState(error = "Sync failed: ${e.message}")
            }
        }
    }

    fun disconnect() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { container.settingsStore.clear() }
            _state.value = ConnectUiState()
        }
    }
}

/** Passes library flows to the UI. Room is the source of truth; the UI never waits on the network. */
class LibraryViewModel(private val container: AppContainer) : ViewModel() {
    val artists = container.library.artists()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val danceTypes = container.library.danceTypes()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun albums(artistId: String) = container.library.albums(artistId)
    fun tracks(albumId: String) = container.library.tracks(albumId)
    fun tracksForDance(danceType: String) = container.library.tracksForDance(danceType)
}

/** Course playlists, stored on the device. */
class PlaylistViewModel(private val container: AppContainer) : ViewModel() {
    val playlists = container.playlists.playlists()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun playlist(id: Long) = container.playlists.playlist(id)
    fun groups(playlistId: Long) = container.playlists.groups(playlistId)

    fun create(name: String, onCreated: (Long) -> Unit) {
        viewModelScope.launch { onCreated(container.playlists.create(name)) }
    }

    fun rename(playlistId: Long, name: String) {
        viewModelScope.launch { container.playlists.rename(playlistId, name) }
    }

    fun delete(playlistId: Long) {
        viewModelScope.launch { container.playlists.deleteById(playlistId) }
    }

    fun addTrack(playlistId: Long, trackId: String, danceType: String?) {
        viewModelScope.launch { container.playlists.addTrack(playlistId, trackId, danceType) }
    }

    fun moveToGroup(item: PlaylistItemRow, danceType: String?) {
        viewModelScope.launch { container.playlists.moveToGroup(item, danceType) }
    }

    fun removeItem(item: PlaylistItemRow) {
        viewModelScope.launch { container.playlists.removeItem(item) }
    }

    /** Plays the whole playlist in position order. */
    fun playAll(playlistId: Long) {
        viewModelScope.launch {
            val ids = container.playlists.orderedTrackIds(playlistId)
            val byId = container.library.tracks(ids).associateBy { it.id }
            container.player.play(ids.mapNotNull { byId[it] })
        }
    }
}
