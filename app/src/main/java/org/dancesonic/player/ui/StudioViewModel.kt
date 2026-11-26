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
import org.dancesonic.player.studio.PlayOnTarget
import org.dancesonic.player.studio.RelayStudio
import org.dancesonic.player.studio.StudioPairing
import org.dancesonic.player.studio.StudioUnavailable

/** What the Config screen shows for paired Studios. */
data class StudioUiState(
    val busy: Boolean = false,
    val message: String? = null,
    val error: String? = null,
    /** The Studios on the folksonic account, once looked up for pairing through folksonic (#104). */
    val relayStudios: List<RelayStudio>? = null,
    /** The 6-digit code to compare with the one Studio shows, while pairing through folksonic waits for the user. */
    val comparisonCode: String? = null,
)

/** Pairs, lists, renames and forgets Studios for the Config screen. */
class StudioViewModel(
    private val container: AppContainer,
) : ViewModel() {
    val pairings: StateFlow<List<StudioPairing>> =
        container.studioPairings.pairings
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** The Studio last chosen with "Play on", kept across restarts; the phone does not reconnect to it by itself. */
    val lastActive: StateFlow<String?> =
        container.studioPairings.lastActive
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)

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

    /** Looks up the Studios that allow remote control through folksonic on the connected account. */
    fun findOnFolksonic() {
        if (_state.value.busy) return
        _state.value = StudioUiState(busy = true, message = "Looking for Studios on folksonic…")
        viewModelScope.launch {
            try {
                val studios = container.studioRemote.relayStudios()
                val note =
                    if (studios.isEmpty()) {
                        "No Studio on this account. Turn on \"Allow remote control through folksonic\" in Studio."
                    } else {
                        "Choose the Studio to pair with."
                    }
                _state.value = StudioUiState(message = note, relayStudios = studios)
            } catch (e: CancellationException) {
                throw e
            } catch (e: StudioUnavailable) {
                _state.value = StudioUiState(error = describe(e.reason))
            }
        }
    }

    /**
     * Pairs with [studio] through folksonic. The comparison code appears in the state as soon as the link is open; the
     * user allows the phone in Studio only when Studio shows the same code.
     */
    fun pairThroughFolksonic(
        studio: RelayStudio,
        phoneName: String,
    ) {
        if (_state.value.busy) return
        _state.value = StudioUiState(busy = true, message = "Connecting to ${studio.name} through folksonic…")
        viewModelScope.launch {
            try {
                val opened =
                    container.studioRemote.pairThroughFolksonic(studio, phoneName) { code ->
                        _state.value =
                            StudioUiState(
                                busy = true,
                                message = "Allow this phone in ${studio.name} only if it shows the same code.",
                                comparisonCode = code,
                            )
                    }
                opened.session.close()
                _state.value = StudioUiState(message = "Paired with ${opened.record.name}.")
            } catch (e: CancellationException) {
                throw e
            } catch (e: StudioUnavailable) {
                _state.value = StudioUiState(error = describe(e.reason))
            }
        }
    }

    /** Where playback runs now: this phone, or one Studio. */
    val target: StateFlow<PlayOnTarget.Target> = container.playOn.target

    /**
     * Connects to [record] and puts playback on it. Playback on the phone stops; the Studio's session stays open until
     * the user switches back or another Studio is chosen.
     */
    fun playOn(record: StudioPairing) {
        if (_state.value.busy) return
        _state.value = StudioUiState(busy = true, message = "Connecting to ${record.name}…")
        viewModelScope.launch {
            try {
                container.player.ensureConnected()
                val opened = container.studioRemote.connect(record)
                if (container.playOn.playOnStudio(opened)) {
                    container.studioPairings.setLastActive(record.id)
                    val via = transportLabel(opened.transport)
                    _state.value = StudioUiState(message = "Playing on ${record.name} ($via).")
                } else {
                    opened.session.close()
                    _state.value = StudioUiState(error = "Playback is not running. Try again.")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: StudioUnavailable) {
                _state.value = StudioUiState(error = describe(e.reason))
            }
        }
    }

    /** Puts playback back on this phone. The Studio keeps playing. */
    fun playOnPhone() {
        container.playOn.playOnPhone()
        viewModelScope.launch { container.studioPairings.setLastActive(null) }
        _state.value = StudioUiState(message = "Playing on this phone.")
    }

    /** Renames a pairing on this phone. The name is the phone's own; Studio is not told. Blank names are refused. */
    fun rename(
        record: StudioPairing,
        name: String,
    ) {
        val clean = studioName(name)
        if (clean == null) {
            _state.value = StudioUiState(error = "Enter a name for the Studio.")
            return
        }
        viewModelScope.launch { container.studioPairings.rename(record.id, clean) }
    }

    /** Removes a pairing and its device key on this phone. Studio keeps the phone listed until it is revoked there. */
    fun forget(record: StudioPairing) {
        // Its key is about to go: playback on it returns to the phone first. The Studio keeps playing.
        if ((target.value as? PlayOnTarget.Target.Studio)?.pairingId == record.id) container.playOn.playOnPhone()
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
            StudioUnavailable.Reason.NOT_FOUND ->
                "Studio is not in reach: not over Bluetooth, not through folksonic, and not on this Wi-Fi."
            StudioUnavailable.Reason.NOT_OFFERED ->
                "Pairing through folksonic needs a connection to a folksonic server with the device relay."
            StudioUnavailable.Reason.NOT_THIS_STUDIO -> "The Studio that answered is not the one that showed the code."
            StudioUnavailable.Reason.REFUSED -> "Studio did not accept this phone."
            StudioUnavailable.Reason.TIMEOUT -> "Studio did not answer in time."
            StudioUnavailable.Reason.LOCAL_NETWORK_BLOCKED ->
                "Studio is on the local network. Allow \"Nearby devices\" for DanceSonic, then try again."
            StudioUnavailable.Reason.FAILED -> "Could not reach Studio."
        }
}

/** Longest name kept for a pairing; longer ones are cut. */
internal const val MAX_STUDIO_NAME = 60

/** [name] trimmed and cut to [MAX_STUDIO_NAME], or null when nothing is left. Control characters are dropped. */
internal fun studioName(name: String): String? =
    name
        .filterNot { it.isISOControl() }
        .trim()
        .take(MAX_STUDIO_NAME)
        .trim()
        .takeIf { it.isNotEmpty() }
