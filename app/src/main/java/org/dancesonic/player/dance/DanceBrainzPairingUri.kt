package org.dancesonic.player.dance

import org.dancesonic.player.account.PairingRequest
import org.dancesonic.player.account.PairingUri
import org.dancesonic.player.subsonic.ServerUrl
import java.net.URI
import java.net.URISyntaxException

/**
 * Parses `dancebrainz://pair?server=<base_url>&token=<code>`, the payload the DanceBrainz account page shows as a
 * QR code. The code is a one-time `dbp_` secret. Everything is checked before use, and the server must be https.
 */
object DanceBrainzPairingUri {
    private const val SCHEME = "dancebrainz"
    private const val HOST = "pair"
    private const val CODE_PREFIX = "dbp_"
    private val CODE_PATTERN = Regex("dbp_[A-Za-z0-9_-]{16,256}")

    /** @throws IllegalArgumentException if the text is not a DanceBrainz pairing code. */
    fun parse(raw: String): PairingRequest {
        val uri =
            try {
                URI(raw.trim())
            } catch (e: URISyntaxException) {
                throw IllegalArgumentException("This is not a DanceBrainz pairing code.", e)
            }
        require(uri.scheme == SCHEME && uri.host == HOST) { "This is not a DanceBrainz pairing code." }

        val params = PairingUri.parseQuery(uri.rawQuery.orEmpty())
        val server = params["server"] ?: throw IllegalArgumentException("The pairing code has no server.")
        val code = params["token"] ?: throw IllegalArgumentException("The pairing code has no code.")
        require(code.startsWith(CODE_PREFIX) && CODE_PATTERN.matches(code)) {
            "The pairing code is malformed."
        }
        return PairingRequest(server = ServerUrl.parse(server), token = code)
    }
}
