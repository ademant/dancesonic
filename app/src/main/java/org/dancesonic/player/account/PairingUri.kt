package org.dancesonic.player.account

import okhttp3.HttpUrl
import org.dancesonic.player.subsonic.ServerUrl
import java.net.URI
import java.net.URLDecoder

/** A decoded pairing QR code: where to redeem the token, and the token itself. */
class PairingRequest(
    val server: HttpUrl,
    val token: String,
) {
    override fun toString(): String = "PairingRequest(server=$server, token=<redacted>)"
}

/**
 * Parses `tinysonic://pair?server=<base_url>&token=<token>`, the payload folksonic shows as a QR
 * code. Everything is validated before use: the scheme and host, an https server, and a token of
 * safe characters and plausible length.
 */
object PairingUri {
    private const val SCHEME = "tinysonic"
    private const val HOST = "pair"
    private val TOKEN_PATTERN = Regex("[A-Za-z0-9_-]{8,256}")

    /** @throws IllegalArgumentException if the text is not a valid pairing code. */
    fun parse(raw: String): PairingRequest {
        val uri = try {
            URI(raw.trim())
        } catch (e: java.net.URISyntaxException) {
            throw IllegalArgumentException("This is not a pairing code.", e)
        }
        require(uri.scheme == SCHEME && uri.host == HOST) { "This is not a pairing code." }

        val params = parseQuery(uri.rawQuery.orEmpty())
        val server = params["server"] ?: throw IllegalArgumentException("The pairing code has no server.")
        val token = params["token"] ?: throw IllegalArgumentException("The pairing code has no token.")
        require(TOKEN_PATTERN.matches(token)) { "The pairing code's token is malformed." }

        return PairingRequest(server = ServerUrl.parse(server), token = token)
    }

    private fun parseQuery(rawQuery: String): Map<String, String> =
        rawQuery.split('&')
            .filter { it.isNotEmpty() }
            .associate { pair ->
                val key = pair.substringBefore('=')
                val value = pair.substringAfter('=', missingDelimiterValue = "")
                URLDecoder.decode(key, Charsets.UTF_8) to URLDecoder.decode(value, Charsets.UTF_8)
            }
}
