package org.dancesonic.player.studio

import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** A link that records what the phone sends and lets the test feed Studio's answers. */
internal class FakeLink(
    override val binding: ByteArray = ByteArray(32) { 0x33 },
    private val log: MutableList<String> = mutableListOf(),
) : StudioLink {
    val sent = Channel<String>(Channel.UNLIMITED)
    override val incoming = Channel<String>(Channel.UNLIMITED)

    override suspend fun send(text: String) {
        log += "send"
        sent.send(text)
    }

    override fun close() {
        incoming.close()
    }

    /** What the phone sent, parsed. */
    suspend fun nextSent(): JsonObject = Json.parseToJsonElement(sent.receive()).jsonObject
}

/** Counts up from zero and records each step, so a test can see that a counter is stored before it is sent. */
internal class RecordingCounter(private val log: MutableList<String>) : StudioCounter {
    private var value = 0L

    override suspend fun advance(): Long {
        value += 1
        log += "counter:$value"
        return value
    }
}

/** A software P-256 key standing in for the Keystore key. */
internal class SoftwareSigner : StudioSigner {
    private val pair =
        java.security.KeyPairGenerator.getInstance("EC").apply {
            initialize(java.security.spec.ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()

    override fun devicePoint(): ByteArray = uncompressedPoint(pair.public)

    override fun sign(data: ByteArray): ByteArray =
        java.security.Signature.getInstance("SHA256withECDSA").run {
            initSign(pair.private)
            update(data)
            sign()
        }

    fun verify(
        data: ByteArray,
        signature: ByteArray,
    ): Boolean =
        java.security.Signature.getInstance("SHA256withECDSA").run {
            initVerify(pair.public)
            update(data)
            verify(signature)
        }
}

internal fun JsonObject.text(key: String): String = this[key]!!.jsonPrimitive.content

internal fun JsonObject.number(key: String): Long = this[key]!!.jsonPrimitive.longOrNull!!

internal fun JsonObject.signature(): ByteArray = StudioWire.decodeBase64Url(text("sig"), 100)!!

class StudioSessionTest {
    private val welcome =
        """{"type":"welcome","protocol":1,"name":"Studio","transport":"network","features":["playback"]}"""

    @Test
    fun `hello is signed over the counter and the binding, and Studio's welcome completes it`() =
        runBlocking {
            val link = FakeLink()
            val signer = SoftwareSigner()
            val session = StudioSession(link, "dev-1", signer, RecordingCounter(mutableListOf()), this)

            val answer = async { session.hello() }
            val hello = link.nextSent()
            link.incoming.send(welcome)

            assertEquals("hello", hello.text("type"))
            assertEquals(1L, hello.number("counter"))
            assertTrue(signer.verify(StudioWire.helloBytes("dev-1", 1, link.binding), hello.signature()))
            assertEquals("Studio", answer.await().name)
            session.close()
        }

    @Test
    fun `a counter is stored before the message that uses it is sent`() =
        runBlocking {
            val log = mutableListOf<String>()
            val link = FakeLink(log = log)
            val session = StudioSession(link, "dev-1", SoftwareSigner(), RecordingCounter(log), this)

            val hello = async { session.hello() }
            link.nextSent()
            link.incoming.send(welcome)
            hello.await()
            val command = async { session.send(StudioCommand.Play) }
            link.nextSent()
            link.incoming.send("""{"type":"result","id":2,"ok":true}""")
            command.await()

            assertEquals(listOf("counter:1", "send", "counter:2", "send"), log)
            session.close()
        }

    @Test
    fun `a command is signed over its exact body and its own counter, and Studio's result answers it`() =
        runBlocking {
            val link = FakeLink()
            val signer = SoftwareSigner()
            val session = StudioSession(link, "dev-1", signer, RecordingCounter(mutableListOf()), this)
            val hello = async { session.hello() }
            link.nextSent()
            link.incoming.send(welcome)
            hello.await()

            val command = async { session.send(StudioCommand.Seek(1234)) }
            val sent = link.nextSent()
            link.incoming.send("""{"type":"result","id":2,"ok":true,"data":null}""")
            val result = command.await()

            assertEquals("command", sent.text("type"))
            assertEquals(2L, sent.number("counter"))
            assertEquals("""{"cmd":"seek","position_ms":1234}""", sent.text("body"))
            assertTrue(
                signer.verify(StudioWire.commandBytes("dev-1", 2, sent.text("body")), sent.signature()),
            )
            assertEquals(true, result.ok)
            session.close()
        }

    @Test
    fun `a refused command throws its error code`() =
        runBlocking {
            val link = FakeLink()
            val session = StudioSession(link, "dev-1", SoftwareSigner(), RecordingCounter(mutableListOf()), this)
            val hello = async { session.hello() }
            link.nextSent()
            link.incoming.send(welcome)
            hello.await()

            val command = async { runCatching { session.send(StudioCommand.Next) } }
            link.nextSent()
            link.incoming.send("""{"type":"result","id":2,"ok":false,"error":"not_on_transport"}""")

            assertEquals("not_on_transport", (command.await().exceptionOrNull() as StudioProtocolError).code)
            session.close()
        }

    @Test
    fun `an error with no counter fails the command that is waiting`() =
        runBlocking {
            val link = FakeLink()
            val session = StudioSession(link, "dev-1", SoftwareSigner(), RecordingCounter(mutableListOf()), this)
            val hello = async { session.hello() }
            link.nextSent()
            link.incoming.send(welcome)
            hello.await()

            val command = async { runCatching { session.send(StudioCommand.Play) } }
            link.nextSent()
            link.incoming.send("""{"type":"error","code":"bad_signature"}""")

            assertEquals("bad_signature", (command.await().exceptionOrNull() as StudioProtocolError).code)
            session.close()
        }

    @Test
    fun `state messages update the playback the phone shows`() =
        runBlocking {
            val link = FakeLink()
            val session = StudioSession(link, "dev-1", SoftwareSigner(), RecordingCounter(mutableListOf()), this)
            val hello = async { session.hello() }
            link.nextSent()
            link.incoming.send(welcome)
            hello.await()

            link.incoming.send("""{"type":"state","playing":false,"queue":[],"rate":1.0,"position_ms":0}""")
            waitUntil { session.state.value != null }

            assertEquals(false, session.state.value!!.playing)
            session.close()
        }

    @Test
    fun `a revoked phone is told it has ended, and the link closes`() =
        runBlocking {
            val link = FakeLink()
            val session = StudioSession(link, "dev-1", SoftwareSigner(), RecordingCounter(mutableListOf()), this)
            val hello = async { session.hello() }
            link.nextSent()
            link.incoming.send(welcome)
            hello.await()

            link.incoming.send("""{"type":"bye","reason":"revoked"}""")
            waitUntil { session.ended.value != null }

            assertEquals("revoked", session.ended.value)
            session.close()
        }

    @Test
    fun `a dropped link ends the session as a lost link, so the phone may switch transport`() =
        runBlocking {
            val link = FakeLink()
            val session = StudioSession(link, "dev-1", SoftwareSigner(), RecordingCounter(mutableListOf()), this)
            val hello = async { session.hello() }
            link.nextSent()
            link.incoming.send(welcome)
            hello.await()

            link.incoming.close()
            waitUntil { session.ended.value != null }

            assertEquals(StudioSession.LINK_LOST, session.ended.value)
            session.close()
        }

    @Test
    fun `a network hint is handed to the phone, which keeps it for the network`() =
        runBlocking {
            val link = FakeLink()
            var hint: StudioMessage.NetworkHint? = null
            val session =
                StudioSession(
                    link,
                    "dev-1",
                    SoftwareSigner(),
                    RecordingCounter(mutableListOf()),
                    this,
                    onHint = { hint = it },
                )
            val hello = async { session.hello() }
            link.nextSent()
            link.incoming.send(welcome)
            hello.await()

            link.incoming.send("""{"type":"network_hint","addresses":["192.168.1.20"],"port":47800}""")
            waitUntil { hint != null }

            assertEquals(StudioMessage.NetworkHint(listOf("192.168.1.20"), 47800), hint)
            session.close()
        }

    private suspend fun waitUntil(condition: () -> Boolean) {
        repeat(200) {
            if (condition()) return
            kotlinx.coroutines.delay(5)
        }
        fail("condition not reached")
    }
}
