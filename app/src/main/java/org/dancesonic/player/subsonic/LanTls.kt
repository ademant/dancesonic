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
 */
internal object LanTls {
    private const val HANDSHAKE_TIMEOUT_MILLIS = 15_000

    /** The SHA-256 fingerprint of [certificate], base64, as stored in [ServerSettings.tlsPin]. */
    fun fingerprint(certificate: X509Certificate): String =
        Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(certificate.encoded))

    /**
     * The pin to store for [url], read from the server now. Null for anything that is not https on a private
     * network. Called once per connect, before the key is sent, so that first use is the only trust-on-first-use.
     */
    suspend fun connectPin(
        url: HttpUrl,
        dispatcher: CoroutineDispatcher = Dispatchers.IO,
    ): String? {
        if (!url.isHttps || !isPrivateNetworkHost(url.host)) return null
        val pin =
            try {
                withContext(dispatcher) { readPin(url) }
            } catch (e: IOException) {
                throw SubsonicException(null, "Could not read the server certificate: ${e.message}", e)
            }
        LanPins.put(url.host, pin)
        return pin
    }

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
            .hostnameVerifier { host, session -> LanPins.pinFor(host) != null || defaultVerifier.verify(host, session) }
    }

    private fun platformTrustManager(): X509TrustManager {
        val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        factory.init(null as KeyStore?)
        return factory.trustManagers.filterIsInstance<X509TrustManager>().first()
    }

    /** Sends each TLS connection to the pinned socket factory when its host has a pin, else to the platform one. */
    private class RoutingSocketFactory(private val platform: SSLSocketFactory) : SSLSocketFactory() {
        private val pinned = ConcurrentHashMap<String, SSLSocketFactory>()

        private fun pick(host: String): SSLSocketFactory =
            if (LanPins.pinFor(host) == null) platform else pinned.getOrPut(host) { pinnedFactory(host) }

        override fun getDefaultCipherSuites(): Array<String> = platform.defaultCipherSuites

        override fun getSupportedCipherSuites(): Array<String> = platform.supportedCipherSuites

        override fun createSocket(
            socket: Socket,
            host: String,
            port: Int,
            autoClose: Boolean,
        ): Socket = pick(host).createSocket(socket, host, port, autoClose)

        override fun createSocket(
            host: String,
            port: Int,
        ): Socket = pick(host).createSocket(host, port)

        override fun createSocket(
            host: String,
            port: Int,
            localHost: InetAddress,
            localPort: Int,
        ): Socket = pick(host).createSocket(host, port, localHost, localPort)

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

        private fun pinnedFactory(host: String): SSLSocketFactory =
            SSLContext.getInstance("TLS").apply { init(null, arrayOf(PinnedTrust(host)), null) }.socketFactory
    }

    /** Accepts a server certificate only when it matches the pin stored for [host]. */
    private class PinnedTrust(private val host: String) : X509TrustManager {
        override fun checkServerTrusted(
            chain: Array<out X509Certificate>,
            authType: String,
        ) {
            val pin = LanPins.pinFor(host) ?: throw CertificateException("No pinned certificate for this server")
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

/**
 * Certificate pins by host, read by the TLS code on every handshake. Kept in memory; the stored copy is in settings.
 */
internal object LanPins {
    private val pins = ConcurrentHashMap<String, String>()

    fun put(
        host: String,
        pin: String,
    ) {
        pins[host] = pin
    }

    fun pinFor(host: String): String? = pins[host]

    /** Forgets every pin. Tests use it so one case does not see another's pin. */
    internal fun clear() {
        pins.clear()
    }

    /** Loads the pin of [settings], if it has one, so connections after a restart use the stored certificate. */
    fun remember(settings: ServerSettings?) {
        val pin = settings?.tlsPin ?: return
        settings.baseUrl.host.let { put(it, pin) }
    }
}
