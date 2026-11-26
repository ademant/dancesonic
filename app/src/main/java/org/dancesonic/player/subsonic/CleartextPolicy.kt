package org.dancesonic.player.subsonic

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import java.io.IOException
import java.util.concurrent.atomic.AtomicReference

/** Raised when a request would use plain http to an address the user has not allowed. Names no address. */
class CleartextRefusedException :
    IOException(
        "Unencrypted http is not allowed to this address. Use https, or allow http for a local server in the " +
            "connection settings.",
    )

/**
 * The app's single rule for plain http (#84). HTTPS is always allowed. Plain http is allowed only to the one server the
 * user opted in for ([ServerSettings.allowCleartext]), which must be on a private network, and to a server of that kind
 * while a connection to it is being set up. Every other http request fails before it is sent, as does an http redirect.
 *
 * Android's network security config cannot name a server chosen at runtime, so the platform permits cleartext and this
 * policy restricts it. Every HTTP client of the app is built through [configure]: Subsonic, streaming and cover art,
 * account and pairing, analysis, DanceBrainz, and the Studio network transport.
 */
internal object CleartextPolicy {
    private data class Origin(
        val host: String,
        val port: Int,
    )

    @Volatile
    private var saved: Origin? = null

    private val connecting = AtomicReference<Origin?>(null)

    /** One plain http setup at a time, so two setups never open http to both servers at once (#127). */
    private val setups = Mutex()

    private fun HttpUrl.origin() = Origin(host.lowercase(), port)

    /** Follows the stored connection: its server may use http only when it was saved with the opt-in. */
    fun remember(settings: ServerSettings?) {
        saved = settings?.takeIf { it.allowCleartext && !it.baseUrl.isHttps }?.baseUrl?.origin()
    }

    /**
     * Runs [block], a connection setup, while [server] may use http. After [block], the stored connection decides
     * again.
     *
     * Contract (#140): [allowCleartext] is the user's http opt-in for this setup. An http [server] needs it and must be
     * a private network address; otherwise nothing runs and [IllegalArgumentException] is thrown. Plain http setups
     * run one at a time (#127): a second waits until the first ends, so only one setup's server is ever open.
     */
    suspend fun <T> whileConnecting(
        server: HttpUrl,
        allowCleartext: Boolean,
        block: suspend () -> T,
    ): T {
        if (server.isHttps) return block()
        require(server.isAllowedServer(allowCleartext)) {
            "Plain http needs the opt-in and a private network address"
        }
        val origin = server.origin()
        return setups.withLock {
            connecting.set(origin)
            try {
                block()
            } finally {
                connecting.set(null)
            }
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
        LocalNetworkAccess
            .configure(LanTls.configure(builder))
            .addInterceptor(interceptor)
            .addNetworkInterceptor(interceptor)
            .followSslRedirects(false)
}
