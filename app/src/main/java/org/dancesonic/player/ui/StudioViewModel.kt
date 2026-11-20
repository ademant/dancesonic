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
import org.dancesonic.player.AppContainer
import org.dancesonic.player.studio.StudioPairing
import org.dancesonic.player.studio.StudioUnavailable

/** What the Config screen shows for paired Studios. */
data class StudioUiState(
    val busy: Boolean = false,
    val message: String? = null,
    val error: String? = null,
)

/** Pairs, lists, renames and forgets Studios for the Config screen. */
class StudioViewModel(private val container: AppContainer) : ViewModel() {
    val pairings: StateFlow<List<StudioPairing>> =
        container.studioPairings.pairings
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _state = MutableStateFlow(StudioUiState())
    val state: StateFlow<StudioUiState> = _state.asStateFlow()

    /** Pairs with the Studio that shows [code]. The phone is accepted in Studio; this waits for that answer. */
    fun pair(
        code: String,
        phoneName: String,
    ) {
        if (_state.value.busy) return
        _state.value = StudioUiState(busy = true, message = "Waiting for Studio to accept this phone…")
        viewModelScope.launch {
            try {
                val opened = container.studioRemote.pair(code, phoneName)
                opened.session.close()
                _state.value = StudioUiState(message = "Paired with ${opened.record.name}.")
            } catch (e: CancellationException) {
                throw e
            } catch (e: StudioUnavailable) {
                _state.value = StudioUiState(error = describe(e.reason))
            }
        }
    }

    /** Removes a pairing and its device key on this phone. Studio keeps the phone listed until it is revoked there. */
    fun forget(record: StudioPairing) {
        viewModelScope.launch { container.studioRemote.forget(record) }
    }

    /** Checks that a paired Studio answers, and reports the result. */
    fun check(record: StudioPairing) {
        if (_state.value.busy) return
        _state.value = StudioUiState(busy = true, message = "Connecting to ${record.name}…")
        viewModelScope.launch {
            try {
                val opened = container.studioRemote.connect(record)
                opened.session.close()
                _state.value = StudioUiState(message = "${record.name} answered.")
            } catch (e: CancellationException) {
                throw e
            } catch (e: StudioUnavailable) {
                _state.value = StudioUiState(error = describe(e.reason))
            }
        }
    }

    private fun describe(reason: StudioUnavailable.Reason): String =
        when (reason) {
            StudioUnavailable.Reason.BAD_CODE -> "That code is not a DanceSonic Studio pairing code."
            StudioUnavailable.Reason.EXPIRED -> "The code has expired. Open \"Pair a phone\" in Studio again."
            StudioUnavailable.Reason.NOT_FOUND -> "Studio is not on this network. Check that it is on the same Wi-Fi."
            StudioUnavailable.Reason.NOT_THIS_STUDIO -> "The Studio that answered is not the one that showed the code."
            StudioUnavailable.Reason.REFUSED -> "Studio did not accept this phone."
            StudioUnavailable.Reason.TIMEOUT -> "Studio did not answer in time."
            StudioUnavailable.Reason.FAILED -> "Could not reach Studio."
        }
}
