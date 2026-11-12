package org.dancesonic.player.account

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Holds the verification step of one pairing (folksonic #103). [await] shows the code through [show] and waits for the
 * user's answer, which [answer] records. [show] receives null once the code is no longer to be shown.
 */
class PairingVerifier(
    private val show: (String?) -> Unit,
    private val waitMillis: Long = DEFAULT_WAIT_MILLIS,
) {
    private var pending: CompletableDeferred<PairingDecision>? = null

    /** Records the user's answer to the code on screen. Does nothing when no code is waiting. */
    fun answer(decision: PairingDecision) {
        pending?.complete(decision)
    }

    /**
     * Shows [code] and returns the user's answer. Throws when no answer comes before the pairing token expires.
     */
    suspend fun await(code: String): PairingDecision {
        val decision = CompletableDeferred<PairingDecision>()
        pending = decision
        show(code)
        try {
            return withTimeoutOrNull(waitMillis) { decision.await() }
                ?: throw AccountException(CODE_EXPIRED)
        } finally {
            pending = null
            show(null)
        }
    }

    companion object {
        /** A pairing token lasts 15 minutes, and so does the time to confirm it. */
        const val DEFAULT_WAIT_MILLIS = 15L * 60_000L
        const val CODE_EXPIRED = "The code expired before it was confirmed. Create a new code on the account page."
    }
}
