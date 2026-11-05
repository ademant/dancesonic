package org.dancesonic.player.account

import org.dancesonic.player.subsonic.ServerSettings
import org.dancesonic.player.subsonic.ServerUrl
import java.net.URI
import java.net.URISyntaxException
import java.net.URLEncoder

/**
 * A connection as a QR code: `dancesonic://connect?server=<base_url>&key=<api_key>`. Used to move the
 * current connection to another device. Unlike a pairing code it carries the API key itself, so it is
 * built only when the user asks for it and is never stored.
 */
object ConnectionUri {
    private const val SCHEME = "dancesonic"
    private const val HOST = "connect"

    /** The connection as a code. The result contains the API key: treat it as a secret. */
    fun build(settings: ServerSettings): String {
        val server = URLEncoder.encode(settings.baseUrl.toString(), "UTF-8")
        val key = URLEncoder.encode(settings.apiKey, "UTF-8")
        return "$SCHEME://$HOST?server=$server&key=$key"
    }

    /** Whether [raw] has this format's scheme, so a scanner can route it before parsing. */
    fun isConnectionCode(raw: String): Boolean = raw.trim().startsWith("$SCHEME://", ignoreCase = true)

    /**
     * Parses a connection code. The server address goes through the same https check as typed input, and the key
     * must not be blank.
     *
     * @throws IllegalArgumentException if the text is not a valid connection code.
     */
    fun parse(raw: String): ServerSettings {
        val uri =
            try {
                URI(raw.trim())
            } catch (e: URISyntaxException) {
                throw IllegalArgumentException("This is not a connection code.", e)
            }
        require(uri.scheme == SCHEME && uri.host == HOST) { "This is not a connection code." }

        val params = PairingUri.parseQuery(uri.rawQuery.orEmpty())
        val server = params["server"] ?: throw IllegalArgumentException("The connection code has no server.")
        val key = params["key"]?.trim().orEmpty()
        require(key.isNotEmpty()) { "The connection code has no API key." }
        return ServerSettings(ServerUrl.parse(server), key)
    }
}
