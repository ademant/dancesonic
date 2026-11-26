package org.dancesonic.player.subsonic

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import java.io.IOException
import java.net.InetAddress
import java.net.Socket
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * TLS to a server on a private network, which usually has a self-signed certificate. There is no CA chain to check,
 * so the server's leaf certificate is pinned instead: its SHA-256 fingerprint is read once, when the server is
 * connected, and every later connection must present that certificate. A certificate that changes fails the request
 * until the server is connected again. Public servers keep the platform's certificate checks.
 *
 * A pin read with [connectPin] is not trusted by itself (#126). The caller compares it with the pin a pairing code
 * carries, or has the user confirm it, and only then makes it active with [LanPins.trustForSetup].
 */
internal object LanTls {
    private const val HANDSHAKE_TIMEOUT_MILLIS = 15_000

    /**
     * The pin of [certificate] in folksonic's form: `sha256/` and the base64 SHA-256 of its public key info, the same
     * value `tinysonic cert fingerprint` prints and the pairing QR code carries. It is stored in
     * [ServerSettings.tlsPin].
     */
    fun fingerprint(certificate: X509Certificate): String =
        "sha256/" +
            Base64.getEncoder().encodeToString(
                MessageDigest.getInstance("SHA-256").digest(certificate.publicKey.encoded),
            )

    /**
     * The pin [url] presents now, or null for anything that is not https on a private network. It is only read: no
     * connection uses it until it is verified and passed to [LanPins.trustForSetup]. Nothing is sent to the server.
     */
    suspend fun connectPin(
        url: HttpUrl,
        dispatcher: CoroutineDispatcher = Dispatchers.IO,
    ): String? {
        if (!needsPin(url)) return null
        LocalNetworkAccess.requireAllowed(url.host)
        return try {
            withContext(dispatcher) { readPin(url) }
        } catch (e: IOException) {
            throw SubsonicException(null, "Could not read the server certificate: ${e.message}", e)
        }
    }

    /** Whether connections to [url] are pinned: https to a private network address. */
    fun needsPin(url: HttpUrl): Boolean = url.isHttps && isPrivateNetworkHost(url.host)

    /** Reads the leaf certificate the server presents. It only reads the certificate and sends nothing. */
    private fun readPin(url: HttpUrl): String {
        val context = SSLContext.getInstance("TLS").apply { init(null, arrayOf(ReadOnlyTrust), null) }
        (context.socketFactory.createSocket(url.host, url.port) as SSLSocket).use { socket ->
            socket.soTimeout = HANDSHAKE_TIMEOUT_MILLIS
            socket.startHandshake()
            return fingerprint(socket.session.peerCertificates.first() as X509Certificate)
        }
    }

    /**
     * Applies the pinned-certificate rules to [builder]. Connections to a host with a pin use the pin; any other host
     * uses the platform checks, unchanged.
     */
    fun configure(builder: OkHttpClient.Builder): OkHttpClient.Builder {
        val platform = SSLSocketFactory.getDefault() as SSLSocketFactory
        val defaultVerifier = HttpsURLConnection.getDefaultHostnameVerifier()
        return builder
            .sslSocketFactory(RoutingSocketFactory(platform), platformTrustManager())
            // A pinned host is identified by its certificate, not by its name, so self-signed certificates
            // without a matching name still connect.
            .hostnameVerifier { host, session ->
                LanPins.pinFor(host, session.peerPort) != null || defaultVerifier.verify(host, session)
            }
    }

    private fun platformTrustManager(): X509TrustManager {
        val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        factory.init(null as KeyStore?)
        return factory.trustManagers.filterIsInstance<X509TrustManager>().first()
    }

    /** Sends each TLS connection to the pinned socket factory when its origin has a pin, else to the platform one. */
    private class RoutingSocketFactory(private val platform: SSLSocketFactory) : SSLSocketFactory() {
        private val pinned = ConcurrentHashMap<PinOrigin, SSLSocketFactory>()

        private fun pick(
            host: String,
            port: Int,
        ): SSLSocketFactory {
            val origin = PinOrigin.of(host, port)
            return if (LanPins.pinFor(origin) == null) platform else pinned.getOrPut(origin) { pinnedFactory(origin) }
        }

        override fun getDefaultCipherSuites(): Array<String> = platform.defaultCipherSuites

        override fun getSupportedCipherSuites(): Array<String> = platform.supportedCipherSuites

        override fun createSocket(
            socket: Socket,
            host: String,
            port: Int,
            autoClose: Boolean,
        ): Socket = pick(host, port).createSocket(socket, host, port, autoClose)

        override fun createSocket(
            host: String,
            port: Int,
        ): Socket = pick(host, port).createSocket(host, port)

        override fun createSocket(
            host: String,
            port: Int,
            localHost: InetAddress,
            localPort: Int,
        ): Socket = pick(host, port).createSocket(host, port, localHost, localPort)

        override fun createSocket(
            host: InetAddress,
            port: Int,
        ): Socket = platform.createSocket(host, port)

        override fun createSocket(
            host: InetAddress,
            port: Int,
            localHost: InetAddress,
            localPort: Int,
        ): Socket = platform.createSocket(host, port, localHost, localPort)

        private fun pinnedFactory(origin: PinOrigin): SSLSocketFactory =
            SSLContext.getInstance("TLS").apply { init(null, arrayOf(PinnedTrust(origin)), null) }.socketFactory
    }

    /** Accepts a server certificate only when it matches the pin trusted for [origin]. */
    private class PinnedTrust(private val origin: PinOrigin) : X509TrustManager {
        override fun checkServerTrusted(
            chain: Array<out X509Certificate>,
            authType: String,
        ) {
            val pin = LanPins.pinFor(origin) ?: throw CertificateException("No pinned certificate for this server")
            val leaf = chain.firstOrNull() ?: throw CertificateException("The server sent no certificate")
            if (fingerprint(leaf) != pin) {
                throw CertificateException("The server certificate changed. Connect to the server again to pin it.")
            }
        }

        override fun checkClientTrusted(
            chain: Array<out X509Certificate>,
            authType: String,
        ) {
            throw CertificateException("Client certificates are not used")
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    /** Used only to read the certificate in [readPin]. It is never used for a request. */
    @Suppress("TrustAllX509TrustManager", "EmptyFunctionBlock")
    private object ReadOnlyTrust : X509TrustManager {
        override fun checkServerTrusted(
            chain: Array<out X509Certificate>,
            authType: String,
        ) {
        }

        override fun checkClientTrusted(
            chain: Array<out X509Certificate>,
            authType: String,
        ) {
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }
}

/** A server's host and port: pins belong to one origin, so another service on the same host gets none (#126). */
internal data class PinOrigin(val host: String, val port: Int) {
    companion object {
        fun of(
            host: String,
            port: Int,
        ) = PinOrigin(host.lowercase(), port)

        fun of(url: HttpUrl) = of(url.host, url.port)
    }
}

/**
 * The certificate pins TLS checks on every handshake, by origin (#126). Two at most: the pin of the stored connection,
 * which follows the settings, and the pin of a connection being set up, which is trusted only after it was verified
 * and is forgotten when the setup ends. A cleared or changed connection leaves no pin behind.
 */
internal object LanPins {
    private class Pinned(val origin: PinOrigin, val pin: String)

    @Volatile
    private var saved: Pinned? = null

    private val connecting = AtomicReference<Pinned?>(null)

    fun pinFor(origin: PinOrigin): String? {
        connecting.get()?.takeIf { it.origin == origin }?.let { return it.pin }
        return saved?.takeIf { it.origin == origin }?.pin
    }

    fun pinFor(
        host: String,
        port: Int,
    ): String? = pinFor(PinOrigin.of(host, port))

    /** The pin stored for [url]'s origin, which the user already trusted. Null when there is none. */
    fun savedPinFor(url: HttpUrl): String? = saved?.takeIf { it.origin == PinOrigin.of(url) }?.pin

    /**
     * Follows the stored connection: only the pin of [settings] stays active, so the pin of a server that was
     * disconnected or replaced is forgotten. Called at start and whenever the settings change.
     */
    fun remember(settings: ServerSettings?) {
        saved = settings?.tlsPin?.let { Pinned(PinOrigin.of(settings.baseUrl), it) }
    }

    /**
     * Trusts [pin] for [url] while a connection is set up. Only for a pin that was compared with a pairing code or
     * confirmed by the user. [endSetup] forgets it; the stored connection then keeps it through [remember].
     */
    fun trustForSetup(
        url: HttpUrl,
        pin: String,
    ) {
        connecting.set(Pinned(PinOrigin.of(url), pin))
    }

    /** Ends a setup, successful or not: its pin is no longer trusted unless it was stored. */
    fun endSetup() {
        connecting.set(null)
    }

    /** Forgets every pin. Tests use it so one case does not see another's pin. */
    internal fun clear() {
        saved = null
        connecting.set(null)
    }
}
