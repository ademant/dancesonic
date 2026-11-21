package org.dancesonic.player.studio

import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StudioPairerTest {
    private val fingerprint = ByteArray(32) { 1 }
    private val secret = ByteArray(16) { 2 }
    private val offer =
        StudioOffer(
            fingerprint,
            secret,
            expiresAt = 10_000,
            bleService = "7c3b0001-5b9e-4c2c-9c6a-2d1f5e0a4b10",
            name = "Studio",
        )

    @Test
    fun `the pairing request proves the secret and signs the transcript over the connection binding`() =
        runBlocking {
            val link = FakeLink()
            val signer = SoftwareSigner()
            val pairer = StudioPairer(signer, noiseKey = ByteArray(32) { 5 })

            val paired = async { pairer.pair(link, offer, "Phone") }
            val request = link.nextSent()
            link.incoming.send("""{"type":"pair_accepted","device":"dev-9"}""")

            val devicePoint = StudioWire.decodeBase64Url(request.text("device_key"), 65)!!
            val transcript = StudioWire.pairTranscript(fingerprint, devicePoint, ByteArray(32) { 5 }, link.binding)
            assertEquals(StudioWire.base64Url(StudioPairer.hmac(secret, transcript)), request.text("proof"))
            assertTrue(signer.verify(transcript, StudioWire.decodeBase64Url(request.text("sig"), 100)!!))
            assertEquals("dev-9", paired.await().deviceId)
        }

    @Test
    fun `Studio pending the decision is not an answer, and a rejection ends the pairing`() =
        runBlocking {
            val link = FakeLink()
            val pairer = StudioPairer(SoftwareSigner(), noiseKey = ByteArray(32))

            val paired = async { runCatching { pairer.pair(link, offer, "Phone") } }
            link.nextSent()
            link.incoming.send("""{"type":"pair_pending"}""")
            link.incoming.send("""{"type":"pair_rejected","reason":"rejected"}""")

            assertEquals("rejected", (paired.await().exceptionOrNull() as StudioProtocolError).code)
        }

    @Test
    fun `an expired or reused code is refused with its code`() =
        runBlocking {
            val link = FakeLink()
            val pairer = StudioPairer(SoftwareSigner(), noiseKey = ByteArray(32))

            val paired = async { runCatching { pairer.pair(link, offer, "Phone") } }
            link.nextSent()
            link.incoming.send("""{"type":"error","code":"secret_used"}""")

            assertEquals("secret_used", (paired.await().exceptionOrNull() as StudioProtocolError).code)
        }

    @Test
    fun `pairing through folksonic sends a signed code request without a proof`() =
        runBlocking {
            val link = FakeLink()
            val signer = SoftwareSigner()
            val noiseKey = ByteArray(32) { 6 }

            val paired = async { StudioCodePairer(signer).pair(link, fingerprint, noiseKey, "Pixel") }
            val request = link.nextSent()
            link.incoming.send("""{"type":"pair_pending"}""")
            link.incoming.send("""{"type":"pair_accepted","device":"dev-4"}""")

            assertEquals("pair_code_request", request.text("type"))
            assertEquals("Pixel", request.text("name"))
            assertEquals(null, request["proof"])
            assertEquals(StudioWire.base64Url(noiseKey), request.text("noise_key"))
            val devicePoint = StudioWire.decodeBase64Url(request.text("device_key"), 65)!!
            val transcript = RelayWire.pairCodeTranscript(fingerprint, devicePoint, noiseKey, link.binding)
            assertTrue(signer.verify(transcript, StudioWire.decodeBase64Url(request.text("sig"), 100)!!))
            assertEquals("dev-4", paired.await().deviceId)
        }

    @Test
    fun `a phone the user rejects in Studio is not paired through folksonic`() =
        runBlocking {
            val link = FakeLink()

            val pairer = StudioCodePairer(SoftwareSigner())
            val paired = async { runCatching { pairer.pair(link, fingerprint, ByteArray(32), "P") } }
            link.nextSent()
            link.incoming.send("""{"type":"pair_rejected","reason":"rejected"}""")

            assertEquals("rejected", (paired.await().exceptionOrNull() as StudioProtocolError).code)
        }
}
