package org.dancesonic.player.account

import okhttp3.HttpUrl
import org.dancesonic.player.subsonic.ServerSettings
import org.dancesonic.player.subsonic.ServerUrl
import java.net.URI
import java.net.URISyntaxException
import java.net.URLEncoder

/** A connection read from a connection code: the Subsonic server, and the DanceBrainz server if the code names one. */
class SharedConnection(
    val server: ServerSettings,
    val danceBrainz: HttpUrl?,
)

/**
 * A connection as a QR code: `dancesonic://connect?server=<base_url>&key=<api_key>&dancebrainz=<base_url>`. Used to
 * move the current settings to another device. The code carries the API key itself, so it is built only when the
 * user asks for it and is never stored. The DanceBrainz address is optional, so older codes still parse.
 */
object ConnectionUri {
    private const val SCHEME = "dancesonic"
    private const val HOST = "connect"

    /** The settings as a code. The result contains the API key: treat it as a secret. */
    fun build(
        settings: ServerSettings,
        danceBrainz: HttpUrl,
    ): String {
        val server = encode(settings.baseUrl.toString())
        val key = encode(settings.apiKey)
        val dance = encode(danceBrainz.toString())
        return "$SCHEME://$HOST?server=$server&key=$key&dancebrainz=$dance"
    }

    /** Whether [raw] has this format's scheme, so a scanner can route it before parsing. */
    fun isConnectionCode(raw: String): Boolean = raw.trim().startsWith("$SCHEME://", ignoreCase = true)

    /**
     * Parses a connection code. Both server addresses go through the same https check as typed input, and the key
     * must not be blank.
     *
     * @throws IllegalArgumentException if the text is not a valid connection code.
     */
    fun parse(raw: String): SharedConnection {
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
        val dance = params["dancebrainz"]?.let { ServerUrl.parseHttps(it) }
        return SharedConnection(ServerSettings(ServerUrl.parse(server), key), dance)
    }

    private fun encode(text: String): String = URLEncoder.encode(text, "UTF-8")
}
