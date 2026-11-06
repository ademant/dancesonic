package org.dancesonic.player.ui

import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import okhttp3.HttpUrl
import org.dancesonic.player.AppContainer
import org.dancesonic.player.dance.DanceBrainzAccountClient
import org.dancesonic.player.dance.DanceBrainzClient
import org.dancesonic.player.dance.DanceBrainzCredential
import org.dancesonic.player.dance.DanceBrainzException
import org.dancesonic.player.dance.DanceBrainzPairingUri
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

    /** True while a DanceBrainz token is stored on this phone. */
    val signedIn: StateFlow<Boolean> =
        container.danceBrainzTokens.credential
            .map { it != null }
            .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** Shows [message] as an error; used when the code scanner itself fails. */
    fun reportError(message: String) {
        _state.value = DanceBrainzUiState(error = message)
    }

    /**
     * Signs in with a pairing code from the account page (scanned or typed). The code must belong to the
     * DanceBrainz server on this config page, so a token is never stored for another server.
     */
    fun signInWithCode(text: String) {
        val pairing =
            try {
                DanceBrainzPairingUri.parse(text)
            } catch (e: IllegalArgumentException) {
                _state.value = DanceBrainzUiState(error = e.message)
                return
            }
        viewModelScope.launch {
            val configured = container.danceBrainzSettings.baseUrl.first()
            if (pairing.server != configured) {
                _state.value =
                    DanceBrainzUiState(error = "This code is for ${pairing.server}. Save that server above first.")
                return@launch
            }
            _state.value = DanceBrainzUiState(busy = true)
            try {
                val credential = DanceBrainzAccountClient(configured).redeem(pairing.token, deviceName())
                container.danceBrainzTokens.save(credential)
                _state.value = DanceBrainzUiState(message = "Signed in to DanceBrainz on this phone.")
            } catch (e: CancellationException) {
                throw e
            } catch (e: DanceBrainzException) {
                _state.value = DanceBrainzUiState(error = e.message)
            }
        }
    }

    /**
     * Signs in with a token copied from the account page. It is checked with the server first, and stored only
     * when accepted.
     */
    fun signInWithToken(text: String) {
        val token = text.trim()
        if (!TOKEN_PATTERN.matches(token)) {
            _state.value = DanceBrainzUiState(error = "This is not a DanceBrainz token. It starts with dbz_.")
            return
        }
        _state.value = DanceBrainzUiState(busy = true)
        viewModelScope.launch {
            try {
                val configured = container.danceBrainzSettings.baseUrl.first()
                DanceBrainzAccountClient(configured).check(token)
                container.danceBrainzTokens.save(DanceBrainzCredential(token, tokenId = null))
                _state.value = DanceBrainzUiState(message = SIGNED_IN_BY_TOKEN)
            } catch (e: CancellationException) {
                throw e
            } catch (e: DanceBrainzException) {
                _state.value = DanceBrainzUiState(error = e.message)
            }
        }
    }

    /**
     * Revokes the token on the server when the app knows its id, then removes it from this phone. A failed
     * revoke still signs out here; the message says so.
     */
    fun signOut() {
        _state.value = DanceBrainzUiState(busy = true)
        viewModelScope.launch {
            val credential = container.danceBrainzTokens.credential.first()
            var note = "Signed out."
            if (credential != null) {
                val id = credential.tokenId
                if (id != null) {
                    try {
                        DanceBrainzAccountClient(container.danceBrainzSettings.baseUrl.first())
                            .revoke(credential.token, id)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: DanceBrainzException) {
                        note = "Signed out here, but the token could not be revoked on the server: ${e.message}"
                    }
                } else {
                    note = "Signed out. Revoke the token on the account page too."
                }
            }
            val unsent = container.shoes.forgetAccount()
            container.danceBrainzTokens.clear()
            if (unsent > 0) note = "$note $unsent shoes ratings were not sent yet and are removed."
            _state.value = DanceBrainzUiState(message = note)
        }
    }

    private fun deviceName(): String = "DanceSonic ${Build.MODEL}".take(MAX_DEVICE_NAME)

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

    private companion object {
        const val SIGNED_IN_BY_TOKEN =
            "Signed in. Signing out only removes the token from this phone; revoke it on the account page too."
        val TOKEN_PATTERN = Regex("dbz_[A-Za-z0-9_-]{16,256}")
        const val MAX_DEVICE_NAME = 60
    }
}
