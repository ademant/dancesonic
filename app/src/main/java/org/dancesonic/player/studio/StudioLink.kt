package org.dancesonic.player.studio

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.ConnectionSpec
import okhttp3.EventListener
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.TlsVersion
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.conscrypt.Conscrypt
import org.dancesonic.player.subsonic.CleartextPolicy
import org.dancesonic.player.subsonic.SubsonicHttp
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * One open connection to Studio, whatever carries it. The session logic works on this interface alone, so it can
 * be tested without a network.
 */
interface StudioLink : AutoCloseable {
    /** The 32-byte channel binding of this connection. Every signed hello and pairing request covers it. */
    val binding: ByteArray

    /** Sends one text message. Throws when the connection is closed. */
    suspend fun send(text: String)

    /** Incoming text messages in order. Closed when the connection ends. */
    val incoming: Channel<String>
}

/** What Studio says about itself at `GET /v1/info`. The fingerprint check happens before any use of it. */
data class StudioInfo(
    val protocol: Int,
    val name: String,
    val noiseKey: ByteArray,
    val fingerprint: ByteArray,
)

/**
 * Opens the network transport: HTTPS to Studio's self-signed certificate, then a WebSocket. The certificate is not
 * checked against a certificate authority. Instead the phone checks Studio's fingerprint, computed from the
 * certificate's public key and the Noise key, against the one in the pairing code, and does that before it sends
 * anything. The channel binding comes from the TLS connection itself, so a signature made on one connection cannot
 * be replayed on another.
 */
class StudioConnector(
    private val timeoutSeconds: Long = CONNECT_TIMEOUT_SECONDS,
    /** Where the blocking HTTP calls run. Tests pass their own (#137). */
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    /** Reads Studio's info, checks its fingerprint against [expectedFingerprint] (or the one it claims when null). */
    suspend fun info(address: HttpUrl): StudioInfo =
        withContext(io) {
            val client = httpClient
            val request =
                Request
                    .Builder()
                    .url(address.newBuilder().encodedPath("/v1/info").build())
                    .get()
                    .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) error("Studio answered HTTP ${response.code}")
                val spki = peerSpki(response)
                val obj = Json.parseToJsonElement(response.body?.string().orEmpty()).jsonObject
                val noiseKey =
                    StudioWire.decodeBase64Url(obj.str("noise_key"), StudioWire.NOISE_KEY_BYTES)
                        ?: error("Studio's info has no noise key")
                StudioInfo(
                    protocol = obj["protocol"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
                    name = obj.str("name"),
                    noiseKey = noiseKey,
                    fingerprint = StudioWire.fingerprint(spki, noiseKey),
                )
            }
        }

    /**
     * Opens the WebSocket. [isStudio] receives the public key of Studio's certificate; it must return true, or the
     * connection is closed before anything is sent.
     */
    suspend fun open(
        address: HttpUrl,
        isStudio: (tlsSpki: ByteArray) -> Boolean,
    ): StudioLink =
        withContext(io) {
            val capture = SocketCapture()
            // Each session needs its own listener, which captures its socket for the channel binding. The derived
            // client shares the connection pool and dispatcher of [httpClient].
            val client =
                httpClient
                    .newBuilder()
                    .eventListener(capture)
                    .build()
            val opened = CompletableDeferred<NetworkStudioLink>()
            val incoming = Channel<String>(Channel.UNLIMITED)
            val request = Request.Builder().url(address.newBuilder().encodedPath("/v1/session").build()).build()
            client.newWebSocket(
                request,
                object : WebSocketListener() {
                    override fun onOpen(
                        webSocket: WebSocket,
                        response: Response,
                    ) {
                        val spki = peerSpkiOrNull(response)
                        val socket = capture.socket
                        if (spki == null || socket == null || !isStudio(spki)) {
                            opened.completeExceptionally(IllegalStateException("This is not the paired Studio"))
                            webSocket.cancel()
                            return
                        }
                        try {
                            val binding =
                                Conscrypt.exportKeyingMaterial(
                                    socket,
                                    StudioWire.EXPORTER_LABEL,
                                    null,
                                    StudioWire.BINDING_BYTES,
                                )
                            opened.complete(NetworkStudioLink(binding, webSocket, incoming))
                        } catch (e: SSLException) {
                            opened.completeExceptionally(e)
                            webSocket.cancel()
                        }
                    }

                    override fun onMessage(
                        webSocket: WebSocket,
                        text: String,
                    ) {
                        if (text.length <= MAX_MESSAGE_CHARS) incoming.trySend(text)
                    }

                    override fun onFailure(
                        webSocket: WebSocket,
                        t: Throwable,
                        response: Response?,
                    ) {
                        opened.completeExceptionally(t)
                        incoming.close(t)
                    }

                    override fun onClosed(
                        webSocket: WebSocket,
                        code: Int,
                        reason: String,
                    ) {
                        incoming.close()
                    }
                },
            )
            opened.await()
        }

    /**
     * The client for Studio, built once per connector (#134). It is derived from the app's shared client, which is
     * built through [CleartextPolicy.configure] (#127): the cleartext rule and Android 17's local network permission
     * (#124) apply, and only the TLS settings are Studio's own.
     */
    private val httpClient: OkHttpClient by lazy {
        SubsonicHttp.base
            .newBuilder()
            .sslSocketFactory(tls.socketFactory, TRUST_ALL)
            // The certificate has a fixed name, and it is checked by fingerprint instead.
            .hostnameVerifier { _, _ -> true }
            .connectionSpecs(
                listOf(ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS).tlsVersions(TlsVersion.TLS_1_3).build()),
            ).connectTimeout(timeoutSeconds, TimeUnit.SECONDS)
            .readTimeout(timeoutSeconds, TimeUnit.SECONDS)
            .pingInterval(PING_SECONDS, TimeUnit.SECONDS)
            .build()
    }

    private fun peerSpki(response: Response): ByteArray =
        peerSpkiOrNull(response) ?: error("Studio did not present a certificate")

    private fun peerSpkiOrNull(response: Response): ByteArray? =
        response.handshake
            ?.peerCertificates
            ?.firstOrNull()
            ?.publicKey
            ?.encoded

    private fun JsonObject.str(key: String): String = this[key]?.jsonPrimitive?.content.orEmpty()

    private class SocketCapture : EventListener() {
        @Volatile
        var socket: SSLSocket? = null

        override fun connectionAcquired(
            call: okhttp3.Call,
            connection: okhttp3.Connection,
        ) {
            socket = connection.socket() as? SSLSocket
        }
    }

    companion object {
        private const val CONNECT_TIMEOUT_SECONDS = 10L
        private const val PING_SECONDS = 20L
        private const val MAX_MESSAGE_CHARS = 64 * 1024

        /** TLS 1.3 through Conscrypt, so the channel binding can be exported. Made once. */
        private val tls: SSLContext by lazy {
            SSLContext.getInstance("TLSv1.3", Conscrypt.newProvider()).apply {
                init(null, arrayOf<TrustManager>(TRUST_ALL), SecureRandom())
            }
        }

        /**
         * Accepts any certificate at the TLS layer. The check that matters is the fingerprint, run after the
         * handshake and before anything is sent.
         */
        @Suppress("TrustAllX509TrustManager", "CustomX509TrustManager")
        private val TRUST_ALL: X509TrustManager =
            object : X509TrustManager {
                override fun checkClientTrusted(
                    chain: Array<out X509Certificate>?,
                    authType: String?,
                ) = Unit

                override fun checkServerTrusted(
                    chain: Array<out X509Certificate>?,
                    authType: String?,
                ) = Unit

                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            }
    }
}

/** A WebSocket to Studio after its fingerprint has been checked. */
internal class NetworkStudioLink(
    override val binding: ByteArray,
    private val socket: WebSocket,
    override val incoming: Channel<String>,
) : StudioLink {
    override suspend fun send(text: String) {
        check(socket.send(text)) { "The connection to Studio is closed" }
    }

    override fun close() {
        socket.close(NORMAL_CLOSE, null)
        incoming.close()
    }

    private companion object {
        const val NORMAL_CLOSE = 1000
    }
}
