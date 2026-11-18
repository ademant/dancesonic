package org.dancesonic.player.dance

import kotlinx.serialization.SerializationException
import org.dancesonic.player.account.refusalCodeOf
import retrofit2.HttpException
import java.io.IOException

/**
 * Runs one DanceBrainz call and turns its failures into [DanceBrainzException], with a message for the user. A refusal
 * that names its reason, such as a removed device, gets that reason's message (see [refusalMessage]).
 * Cancellation is not caught: it is not an IOException.
 */
internal suspend fun <T> danceBrainzCall(block: suspend () -> T): T =
    try {
        block()
    } catch (e: IOException) {
        throw DanceBrainzException("Could not reach DanceBrainz: ${e.message ?: "no answer"}", e)
    } catch (e: HttpException) {
        val reason = refusalMessage(refusalCodeOf(e.response()?.errorBody()?.string()))
        throw DanceBrainzException(reason ?: messageFor(e.code()), e, httpCode = e.code())
    } catch (e: SerializationException) {
        throw DanceBrainzException("DanceBrainz answered in a form the app does not know", e)
    }

/**
 * The message for a DanceBrainz refusal code that concerns this phone's sign-in (#69), or null for any other code. A
 * paired device is refused with these when it is not approved yet, was removed, or sent an invalid or repeated proof.
 */
internal fun refusalMessage(code: String?): String? =
    when (code) {
        "pairing_pending" -> "This phone is not approved yet. Approve it on the DanceBrainz account page."
        "device_unknown" -> "This phone was removed on the DanceBrainz account page. Pair it again."
        "proof_invalid" -> "This phone's key was refused. Pair the phone again."
        "proof_replayed" -> "DanceBrainz refused a repeated request. Try again."
        else -> null
    }

private fun messageFor(code: Int): String =
    when (code) {
        UNAUTHORIZED -> "The code or token was not accepted. It may be wrong, expired or revoked."
        FORBIDDEN -> "This account is not allowed to do that."
        PRECONDITION_FAILED -> "The chapters changed on DanceBrainz since they were shown. Try again."
        else -> "DanceBrainz answered with HTTP $code"
    }

private const val UNAUTHORIZED = 401
private const val FORBIDDEN = 403
private const val PRECONDITION_FAILED = 412
