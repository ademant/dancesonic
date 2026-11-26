package org.dancesonic.player.studio

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.dancesonic.player.subsonic.Memo
import org.dancesonic.player.subsonic.ServerAuth
import org.dancesonic.player.subsonic.ServerSettings
import java.io.IOException
import java.util.concurrent.TimeUnit

/** A Studio listed on the account's relay, with the record it published. */
class RelayStudio(
    val id: String,
    val name: String,
    val record: StudioRecord,
)

/** The server has no relay: not folksonic, an older folksonic, or no server connected. */
class RelayNotOffered : IOException("the server does not offer the folksonic relay")

/**
 * One relay connection to one Studio. [incoming] holds the payloads Studio sends to this phone; it closes when the
 * connection drops or folksonic reports that Studio is not connected.
 */
interface RelayPipe : AutoCloseable {
    /** Sends one payload (`[kind][Noise message]`) to Studio. Throws when the connection is closed. */
    suspend fun send(payload: ByteArray)

    val incoming: ReceiveChannel<ByteArray>

    override fun close()
}

/** What the phone needs from folksonic's relay. [RelayClient] is the real one; tests use a fake. */
interface RelayEndpoint {
    /** The Studios on the account. Throws [RelayNotOffered] when the server has no relay. */
    suspend fun studios(): List<RelayStudio>

    /** Publishes the phone's record, which folksonic needs before it opens the phone's connection. */
    suspend fun publishPhone(
        phoneId: String,
        name: String,
    )

    /** Opens the phone's relay connection for the session with [studioId]. */
    suspend fun open(
        phoneId: String,
        studioId: String,
    ): RelayPipe

    /** Removes the Studio's record from the account, which also ends its relay connection. */
    suspend fun removeStudio(studioId: String)
}

@Serializable
private class RelayDeviceDto(
    val id: String? = null,
    val kind: String? = null,
    val name: String? = null,
    val publicKey: String? = null,
)

@Serializable
private class RelayDevicesDto(
    val devices: List<RelayDeviceDto>? = null,
)

@Serializable
private class RelayRecordBody(
    val kind: String,
    val name: String,
    val publicKey: String,
)

/**
 * folksonic's device relay (ademant/folksonic#159): the account's device records and the WebSocket that carries
 * frames between them. The credential goes in a header, never in the URL: the `DPoP` proof the client's interceptor
 * adds for a paired device, or `X-Api-Key`. Only used for folksonic, the server that takes the key in a header.
 */
class RelayClient(
    private val client: OkHttpClient,
    private val settings: ServerSettings,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : RelayEndpoint {
    /** The Studios on the account, with a record this version understands. */
    override suspend fun studios(): List<RelayStudio> =
        withContext(io) {
            client.newCall(authorized(devicesUrl()).get().build()).execute().use { response ->
                checkOffered(response)
                val body = response.peekBody(MAX_ANSWER_BYTES).string()
                val devices = json.decodeFromString(RelayDevicesDto.serializer(), body).devices.orEmpty()
                devices.mapNotNull { device ->
                    val id = device.id?.takeIf { device.kind == "studio" && RelayWire.validDeviceId(it) }
                    val record = device.publicKey?.let(StudioRecord::parse)
                    if (id == null || record == null) return@mapNotNull null
                    RelayStudio(
                        id,
                        device.name
                            .orEmpty()
                            .take(MAX_NAME)
                            .ifBlank { "Studio" },
                        record,
                    )
                }
            }
        }

    /**
     * Publishes the phone's record, which folksonic needs before it opens the phone's relay connection. The phone's
     * keys are per Studio and travel inside the Noise session, so the record holds only the version byte.
     */
    override suspend fun publishPhone(
        phoneId: String,
        name: String,
    ) = withContext(io) {
        val body = RelayRecordBody(kind = "phone", name = name.take(MAX_NAME), publicKey = PHONE_RECORD)
        val request =
            authorized(devicesUrl().newBuilder().addPathSegment(phoneId).build())
                .put(json.encodeToString(RelayRecordBody.serializer(), body).toRequestBody(JSON))
                .build()
        client.newCall(request).execute().use(::checkOffered)
    }

    /**
     * Removes the Studio's record from the account, so it is no longer listed for any of its devices, and folksonic
     * drops its relay connection at once. A record that is already gone counts as removed.
     */
    override suspend fun removeStudio(studioId: String) {
        require(RelayWire.validDeviceId(studioId)) { "not a relay device id" }
        withContext(io) {
            val url = devicesUrl().newBuilder().addPathSegment(studioId).build()
            client.newCall(authorized(url).delete().build()).execute().use { response ->
                if (response.code != HTTP_NOT_FOUND) checkOffered(response)
            }
        }
    }

    /** Opens the phone's relay connection, for the session with [studioId]. Returns once the WebSocket is open. */
    override suspend fun open(
        phoneId: String,
        studioId: String,
    ): RelayPipe {
        val url = relayUrl("api/relay/connect").newBuilder().addQueryParameter("device", phoneId).build()
        val pipe = OkHttpRelayPipe(studioId)
        val socket = socketClient.newWebSocket(authorized(url).get().build(), pipe.listener)
        pipe.socket = socket
        var open = false
        try {
            withTimeout(OPEN_TIMEOUT_MILLIS) { pipe.opened.await() }
            open = true
        } catch (e: TimeoutCancellationException) {
            throw IOException("the relay connection did not open in time", e)
        } finally {
            if (!open) socket.cancel()
        }
        return pipe
    }

    /** The client for the relay socket: no read timeout, and pings to keep the socket open. Built once (#134). */
    private val socketClient: OkHttpClient get() = socketClients[client]

    private fun devicesUrl(): HttpUrl = relayUrl("api/relay/devices")

    private fun relayUrl(path: String): HttpUrl =
        settings.baseUrl
            .newBuilder()
            .addPathSegments(path)
            .build()

    private fun authorized(url: HttpUrl): Request.Builder {
        val builder = Request.Builder().url(url)
        // A paired device is signed by the client's DPoP interceptor; a key goes in the header.
        settings.apiKey?.let { builder.header(API_KEY_HEADER, it) }
        return builder
    }

    private fun checkOffered(response: Response) {
        when {
            response.code == HTTP_NOT_FOUND || response.code == HTTP_METHOD_NOT_ALLOWED -> throw RelayNotOffered()
            !response.isSuccessful -> throw IOException("the relay request failed with HTTP ${response.code}")
        }
    }

    companion object {
        /** Socket clients by the client they derive from; a RelayClient is made per use, the socket client is not. */
        private val socketClients =
            Memo<OkHttpClient, OkHttpClient>(2) { client ->
                client
                    .newBuilder()
                    // folksonic drops a connection after 60 seconds of silence; pings keep it open between commands.
                    .pingInterval(PING_SECONDS, TimeUnit.SECONDS)
                    .readTimeout(0, TimeUnit.SECONDS)
                    .build()
            }

        /** Whether [settings] is a server whose relay the phone may use: folksonic, by key in a header or a device. */
        fun usable(settings: ServerSettings): Boolean = settings.auth is ServerAuth.Device || settings.keyInHeader

        /** The phone's record: version 1, no keys. */
        const val PHONE_RECORD = "AQ"

        private const val API_KEY_HEADER = "X-Api-Key"

        private const val MAX_ANSWER_BYTES = 64L * 1024
        private const val MAX_NAME = 64
        private const val OPEN_TIMEOUT_MILLIS = 10_000L
        private const val PING_SECONDS = 25L
        private const val HTTP_NOT_FOUND = 404
        private const val HTTP_METHOD_NOT_ALLOWED = 405
        private val JSON = "application/json".toMediaType()
        private val json = Json { ignoreUnknownKeys = true }
    }
}

/** A [RelayPipe] on an OkHttp WebSocket. Frames from any device but [studioId] are dropped. */
private class OkHttpRelayPipe(
    private val studioId: String,
) : RelayPipe {
    @Volatile var socket: WebSocket? = null
    val opened = CompletableDeferred<Unit>()
    override val incoming = Channel<ByteArray>(INBOX)

    val listener =
        object : WebSocketListener() {
            override fun onOpen(
                webSocket: WebSocket,
                response: Response,
            ) {
                opened.complete(Unit)
            }

            override fun onMessage(
                webSocket: WebSocket,
                bytes: ByteString,
            ) {
                val (from, payload) =
                    runCatching { RelayWire.decodeFrame(bytes.toByteArray()) }.getOrNull() ?: return
                // A phone that cannot keep up loses the session rather than buffering without bound.
                if (from == studioId && incoming.trySend(payload).isFailure) webSocket.cancel()
            }

            override fun onMessage(
                webSocket: WebSocket,
                text: String,
            ) {
                // `{"type":"undelivered","to":…}`: Studio is not connected, so the session cannot go on.
                val type =
                    runCatching {
                        Json
                            .parseToJsonElement(text)
                            .jsonObject["type"]
                            ?.jsonPrimitive
                            ?.content
                    }
                if (type.getOrNull() == "undelivered") {
                    incoming.close(IOException("Studio is not connected to the relay"))
                    webSocket.close(NORMAL_CLOSURE, null)
                }
            }

            override fun onClosing(
                webSocket: WebSocket,
                code: Int,
                reason: String,
            ) {
                incoming.close()
                webSocket.close(NORMAL_CLOSURE, null)
            }

            override fun onFailure(
                webSocket: WebSocket,
                t: Throwable,
                response: Response?,
            ) {
                // The response code tells a missing record or relay from a dropped connection; nothing else is kept.
                val failure =
                    if (response?.code == HTTP_NOT_FOUND) RelayNotOffered() else IOException("relay connection failed")
                opened.completeExceptionally(failure)
                incoming.close(failure)
            }
        }

    override suspend fun send(payload: ByteArray) {
        val sent = socket?.send(RelayWire.encodeFrame(studioId, payload).toByteString()) ?: false
        if (!sent) throw IOException("the relay connection is closed")
    }

    override fun close() {
        incoming.close()
        socket?.close(NORMAL_CLOSURE, null)
    }

    private companion object {
        const val INBOX = 64
        const val NORMAL_CLOSURE = 1000
        const val HTTP_NOT_FOUND = 404
    }
}
