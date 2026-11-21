package org.dancesonic.player.studio

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.dancesonic.player.subsonic.ServerAuth
import org.dancesonic.player.subsonic.ServerSettings
import org.dancesonic.player.testsupport.TestSubsonicServer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.ByteBuffer
import java.util.UUID

/** folksonic's relay endpoints, against a local server (#104). The key goes in a header, never in the URL. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RelayClientTest {
    private lateinit var server: TestSubsonicServer
    private lateinit var relay: RelayClient

    private val studioId = "studio-abc"

    @Before
    fun setUp() {
        server = TestSubsonicServer()
        relay = RelayClient(server.plain, ServerSettings(server.server.url("/"), "test-api-key", keyInHeader = true))
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun record(): String {
        val uuid = UUID.randomUUID()
        val uuidBytes = ByteBuffer.allocate(16).putLong(uuid.mostSignificantBits).putLong(uuid.leastSignificantBits)
        return StudioWire.base64Url(byteArrayOf(1) + ByteArray(32) { 3 } + uuidBytes.array() + ByteArray(91) { 4 })
    }

    @Test
    fun `the account's Studios are listed, without phones or records this version cannot read`() =
        runBlocking<Unit> {
            server.enqueueJson(
                """{"devices":[
                    {"id":"studio-abc","kind":"studio","name":"Hall laptop","publicKey":"${record()}"},
                    {"id":"phone-1","kind":"phone","name":"Pixel","publicKey":"AQ"},
                    {"id":"studio-old","kind":"studio","name":"Old","publicKey":"Ag"}
                ]}""",
            )

            val studios = relay.studios()
            val request = server.server.takeRequest()

            assertEquals(listOf("studio-abc"), studios.map { it.id })
            assertEquals("Hall laptop", studios.single().name)
            assertEquals("/api/relay/devices", request.path)
            assertEquals("test-api-key", request.getHeader("X-Api-Key"))
            assertFalse(request.path.orEmpty().contains("test-api-key"))
        }

    @Test
    fun `a server without the relay is reported as such`() {
        server.enqueueError(404)

        assertThrows(RelayNotOffered::class.java) { runBlocking { relay.studios() } }
    }

    @Test
    fun `the phone's record holds only the version byte`() =
        runBlocking<Unit> {
            server.enqueueJson("{}")

            relay.publishPhone("phone-xyz", "Pixel")
            val request = server.server.takeRequest()
            val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject

            assertEquals("PUT", request.method)
            assertEquals("/api/relay/devices/phone-xyz", request.path)
            assertEquals("phone", body["kind"]?.jsonPrimitive?.content)
            assertEquals("AQ", body["publicKey"]?.jsonPrimitive?.content)
            assertEquals("Pixel", body["name"]?.jsonPrimitive?.content)
            assertEquals("test-api-key", request.getHeader("X-Api-Key"))
        }

    @Test
    fun `the relay connection passes frames from Studio only, and ends when Studio is not connected`() =
        runBlocking<Unit> {
            val fromPhone = Channel<ByteArray>(Channel.UNLIMITED)
            val studioSide =
                object : WebSocketListener() {
                    override fun onOpen(
                        webSocket: WebSocket,
                        response: Response,
                    ) {
                        webSocket.send(RelayWire.encodeFrame(studioId, byteArrayOf(2, 9)).toByteString())
                        webSocket.send(RelayWire.encodeFrame("phone-other", byteArrayOf(2, 8)).toByteString())
                    }

                    override fun onMessage(
                        webSocket: WebSocket,
                        bytes: ByteString,
                    ) {
                        fromPhone.trySend(bytes.toByteArray())
                        webSocket.send("""{"type":"undelivered","to":"$studioId"}""")
                    }

                    override fun onClosing(
                        webSocket: WebSocket,
                        code: Int,
                        reason: String,
                    ) {
                        // Completes the close handshake, so the server can shut down after the test.
                        webSocket.close(1000, null)
                    }
                }
            server.server.enqueue(MockResponse().withWebSocketUpgrade(studioSide))

            val pipe = relay.open("phone-xyz", studioId)
            val first = withTimeout(5_000) { pipe.incoming.receive() }
            pipe.send(byteArrayOf(2, 7))
            val sent = withTimeout(5_000) { fromPhone.receive() }
            val after = withTimeout(5_000) { pipe.incoming.receiveCatching() }
            val request = server.server.takeRequest()

            assertArrayEquals(byteArrayOf(2, 9), first)
            val (to, payload) = RelayWire.decodeFrame(sent)
            assertEquals(studioId, to)
            assertArrayEquals(byteArrayOf(2, 7), payload)
            assertTrue(after.isClosed)
            assertEquals("/api/relay/connect?device=phone-xyz", request.path)
            assertEquals("test-api-key", request.getHeader("X-Api-Key"))
            pipe.close()
        }

    @Test
    fun `only folksonic connections use the relay`() {
        val base = server.server.url("/")

        assertTrue(RelayClient.usable(ServerSettings(base, "k", keyInHeader = true)))
        assertTrue(RelayClient.usable(ServerSettings(base, ServerAuth.Device("alias"))))
        assertFalse(RelayClient.usable(ServerSettings(base, "k")))
        assertNull(ServerSettings(base, ServerAuth.Device("alias")).apiKey)
    }
}
