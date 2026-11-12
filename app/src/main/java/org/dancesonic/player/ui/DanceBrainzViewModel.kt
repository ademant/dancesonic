package org.dancesonic.player.ui

import android.database.sqlite.SQLiteException
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
import org.dancesonic.player.data.DanceRefresh
import org.dancesonic.player.subsonic.DeviceKeys
import org.dancesonic.player.subsonic.KeystoreDeviceSigner
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

    /**
     * Re-checks the DanceBrainz data of the library: the recent ones, or every recording when [full]. One check runs
     * at a time; the state shows its progress and the result.
     */
    fun refreshDance(full: Boolean) {
        if (_state.value.busy) return
        viewModelScope.launch {
            _state.value = DanceBrainzUiState(busy = true, message = "Checking DanceBrainz…")
            try {
                val result =
                    container.library.refreshDanceBrainz(full) { done, total ->
                        _state.value = DanceBrainzUiState(busy = true, message = "Checked $done of $total recordings…")
                    }
                _state.value = DanceBrainzUiState(message = refreshMessage(result))
            } catch (e: SQLiteException) {
                val reason = e.javaClass.simpleName
                _state.value = DanceBrainzUiState(error = "The dance data could not be refreshed ($reason).")
            }
        }
    }

    private fun refreshMessage(result: DanceRefresh): String =
        when {
            result.checked == 0 -> "Nothing to check: the library is empty or every recording was checked recently."
            result.failed == 0 -> "Checked ${result.checked} recordings."
            else ->
                "Checked ${result.checked} recordings; ${result.failed} got no answer and keep their old data. " +
                    "Try again later."
        }

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
                pairThisPhone(configured, pairing.token)
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
                val credential = DanceBrainzCredential.Token(token, tokenId = null)
                DanceBrainzAccountClient(configured).check(credential)
                container.danceBrainzTokens.save(credential)
                _state.value = DanceBrainzUiState(message = SIGNED_IN_BY_TOKEN)
            } catch (e: CancellationException) {
                throw e
            } catch (e: DanceBrainzException) {
                _state.value = DanceBrainzUiState(error = e.message)
            }
        }
    }

    /**
     * Signs out: a device key is deleted and a token is revoked on the server when its id is known. The message says
     * what the owner still has to do on the account page.
     */
    fun signOut() {
        _state.value = DanceBrainzUiState(busy = true)
        viewModelScope.launch {
            val note = revokeCredential(container.danceBrainzTokens.credential.first())
            val unsent = container.edits.forgetAccount()
            container.danceBrainzTokens.clear()
            _state.value =
                DanceBrainzUiState(
                    message = if (unsent > 0) "$note $unsent changes were not sent yet and are removed." else note,
                )
        }
    }

    /**
     * Pairs this phone as a device (dancesonic #69). The key stays in the Keystore and only its name is stored, and
     * only once the owner has approved the device. A pairing that fails leaves no key behind.
     */
    private suspend fun pairThisPhone(
        server: HttpUrl,
        code: String,
    ) {
        val alias = DeviceKeys.create()
        var paired = false
        try {
            val account = DanceBrainzAccountClient(server)
            account.pairDevice(code, deviceName(), KeystoreDeviceSigner(alias).publicJwk())
            _state.value = DanceBrainzUiState(busy = true, message = WAITING_FOR_APPROVAL)
            val credential = DanceBrainzCredential.Device(alias)
            account.awaitApproval(credential)
            container.danceBrainzTokens.save(credential)
            paired = true
            _state.value = DanceBrainzUiState(message = "Signed in to DanceBrainz on this phone.")
        } finally {
            if (!paired) DeviceKeys.delete(alias)
        }
    }

    /** Removes what this phone holds for [credential] and returns the note for the user. */
    private suspend fun revokeCredential(credential: DanceBrainzCredential?): String =
        when (credential) {
            null -> "Signed out."
            is DanceBrainzCredential.Device -> {
                DeviceKeys.delete(credential.alias)
                "Signed out. Remove this phone on the DanceBrainz account page too."
            }
            is DanceBrainzCredential.Token -> revokeToken(credential)
        }

    private suspend fun revokeToken(credential: DanceBrainzCredential.Token): String {
        val id = credential.tokenId ?: return "Signed out. Revoke the token on the account page too."
        return try {
            DanceBrainzAccountClient(container.danceBrainzSettings.baseUrl.first()).revoke(credential, id)
            "Signed out."
        } catch (e: CancellationException) {
            throw e
        } catch (e: DanceBrainzException) {
            "Signed out here, but the token could not be revoked on the server: ${e.message}"
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
        const val WAITING_FOR_APPROVAL = "Waiting for approval on the account page…"
    }
}
