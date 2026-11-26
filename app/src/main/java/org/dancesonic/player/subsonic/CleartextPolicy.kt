package org.dancesonic.player.subsonic

import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import java.io.IOException
import java.util.concurrent.atomic.AtomicReference

/** Raised when a request would use plain http to an address the user has not allowed. Names no address. */
class CleartextRefusedException : IOException(
    "Unencrypted http is not allowed to this address. Use https, or allow http for a local server in the connection " +
        "settings.",
)

/**
 * The app's single rule for plain http (#84). HTTPS is always allowed. Plain http is allowed only to the one server the
 * user opted in for ([ServerSettings.allowCleartext]), which must be on a private network, and to a server of that kind
 * while a connection to it is being set up. Every other http request fails before it is sent, as does an http redirect.
 *
 * Android's network security config cannot name a server chosen at runtime, so the platform permits cleartext and this
 * policy restricts it. Every HTTP client of the app is built through [configure]: Subsonic, streaming and cover art,
 * account and pairing, analysis, and DanceBrainz.
 */
internal object CleartextPolicy {
    private data class Origin(val host: String, val port: Int)

    @Volatile
    private var saved: Origin? = null

    private val connecting = AtomicReference<Origin?>(null)

    private fun HttpUrl.origin() = Origin(host.lowercase(), port)

    /** Follows the stored connection: its server may use http only when it was saved with the opt-in. */
    fun remember(settings: ServerSettings?) {
        saved = settings?.takeIf { it.allowCleartext && !it.baseUrl.isHttps }?.baseUrl?.origin()
    }

    /**
     * Runs [block], a connection setup, while [server] may use http. Only for an address that passed
     * [ServerUrl.parse] with the opt-in. After [block], the stored connection decides again.
     */
    suspend fun <T> whileConnecting(
        server: HttpUrl,
        block: suspend () -> T,
    ): T {
        if (server.isHttps) return block()
        require(isPrivateNetworkHost(server.host)) { "Plain http is only possible for a private network address" }
        val origin = server.origin()
        connecting.set(origin)
        try {
            return block()
        } finally {
            connecting.compareAndSet(origin, null)
        }
    }

    /** Whether a request to [url] may be sent. */
    fun permits(url: HttpUrl): Boolean {
        if (url.isHttps) return true
        val origin = url.origin()
        return origin == saved || origin == connecting.get()
    }

    /**
     * Checks each request. Added both as an application interceptor, so nothing is connected for a refused request,
     * and as a network interceptor, so a redirect is checked too.
     */
    val interceptor =
        Interceptor { chain ->
            if (!permits(chain.request().url)) throw CleartextRefusedException()
            chain.proceed(chain.request())
        }

    /**
     * Applies the transport rules to [builder]: this policy, no redirects across schemes (an https to http redirect
     * would downgrade the connection), the certificate pins of private servers ([LanTls]), and Android 17's local
     * network permission ([LocalNetworkAccess]).
     */
    fun configure(builder: OkHttpClient.Builder): OkHttpClient.Builder =
        LocalNetworkAccess.configure(LanTls.configure(builder))
            .addInterceptor(interceptor)
            .addNetworkInterceptor(interceptor)
            .followSslRedirects(false)
}
