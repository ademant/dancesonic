package org.dancesonic.player.dance

import kotlinx.serialization.SerializationException
import retrofit2.HttpException
import java.io.IOException

/**
 * Runs one DanceBrainz call and turns its failures into [DanceBrainzException], with a message for the user.
 * Cancellation is not caught: it is not an IOException.
 */
internal suspend fun <T> danceBrainzCall(block: suspend () -> T): T =
    try {
        block()
    } catch (e: IOException) {
        throw DanceBrainzException("Could not reach DanceBrainz: ${e.message ?: "no answer"}", e)
    } catch (e: HttpException) {
        throw DanceBrainzException(messageFor(e.code()), e, httpCode = e.code())
    } catch (e: SerializationException) {
        throw DanceBrainzException("DanceBrainz answered in a form the app does not know", e)
    }

private fun messageFor(code: Int): String =
    when (code) {
        UNAUTHORIZED -> "The code or token was not accepted. It may be wrong, expired or revoked."
        FORBIDDEN -> "This account is not allowed to do that."
        else -> "DanceBrainz answered with HTTP $code"
    }

private const val UNAUTHORIZED = 401
private const val FORBIDDEN = 403
