package org.dancesonic.player.subsonic

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import org.dancesonic.player.testsupport.SoftwareDeviceSigner
import org.dancesonic.player.testsupport.TestSubsonicServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Base64

/** The proof must be what folksonic's `internal/dpop` verifies: an ES256 JWS with the claims it checks. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DeviceProofTest {
    private val signer = SoftwareDeviceSigner()
    private val htu = "https://music.example.com/folksonic/rest/ping.view"

    @Test
    fun `the proof has the header, claims and signature a verifier expects`() {
        val proof = DeviceProof.build(signer, "GET", htu, nowSeconds = 1_700_000_000L, jti = "jti-1")

        val parts = proof.split('.')
        assertEquals(3, parts.size)
        val header = json(parts[0])
        assertEquals("dpop+jwt", header["typ"]!!.jsonPrimitive.content)
        assertEquals("ES256", header["alg"]!!.jsonPrimitive.content)
        val jwk = header["jwk"]!!.jsonObject
        assertEquals("EC", jwk["kty"]!!.jsonPrimitive.content)
        assertEquals("P-256", jwk["crv"]!!.jsonPrimitive.content)
        assertEquals(43, jwk["x"]!!.jsonPrimitive.content.length)
        assertEquals(43, jwk["y"]!!.jsonPrimitive.content.length)

        val claims = json(parts[1])
        assertEquals("jti-1", claims["jti"]!!.jsonPrimitive.content)
        assertEquals("GET", claims["htm"]!!.jsonPrimitive.content)
        assertEquals(htu, claims["htu"]!!.jsonPrimitive.content)
        assertEquals(1_700_000_000L, claims["iat"]!!.jsonPrimitive.content.toLong())
    }

    @Test
    fun `the signature verifies with the key in the header`() {
        val proof = DeviceProof.build(signer, "POST", htu, nowSeconds = 1L)
        val parts = proof.split('.')

        assertTrue(signer.verifies((parts[0] + "." + parts[1]).toByteArray(Charsets.US_ASCII), decode(parts[2])))
    }

    @Test
    fun `a change to the signed claims fails verification`() {
        val proof = DeviceProof.build(signer, "GET", htu, nowSeconds = 1L)
        val parts = proof.split('.')
        val tampered = json(parts[1]).toString().replace("GET", "DELETE")
        val tamperedPayload = Base64.getUrlEncoder().withoutPadding().encodeToString(tampered.toByteArray())

        assertFalse(
            signer.verifies((parts[0] + "." + tamperedPayload).toByteArray(Charsets.US_ASCII), decode(parts[2])),
        )
    }

    @Test
    fun `each proof has its own jti`() {
        val first = json(DeviceProof.build(signer, "GET", htu, 1L).split('.')[1])["jti"]
        val second = json(DeviceProof.build(signer, "GET", htu, 1L).split('.')[1])["jti"]

        assertTrue(first != second)
    }

    @Test
    fun `der signatures are converted to raw r and s, padded to 32 bytes`() {
        // r has its high bit set, so DER adds a zero byte; s is short and needs padding.
        val r = ByteArray(32) { 0xff.toByte() }
        val s = ByteArray(32).also { it[31] = 1 }
        val der = byteArrayOf(0x30, 0x26, 0x02, 0x21, 0x00) + r + byteArrayOf(0x02, 0x01, 0x01)

        assertEquals(64, derToRaw(der).size)
        assertEquals(r.toList(), derToRaw(der).copyOfRange(0, 32).toList())
        assertEquals(s.toList(), derToRaw(der).copyOfRange(32, 64).toList())
    }

    @Test
    fun `the interceptor signs requests to the device's server only`() {
        val server = TestSubsonicServer()
        try {
            val device = ServerSettings(server.settings.baseUrl, ServerAuth.Device("alias"))
            val http = server.plain.newBuilder().addInterceptor(DeviceProofInterceptor({ device }, { signer })).build()
            server.server.enqueue(MockResponse().setBody("{}"))

            http.newCall(
                okhttp3.Request.Builder().url(server.server.url("/rest/ping.view?c=DanceSonic&apiKey=none")).build(),
            ).execute().close()

            val sent = server.server.takeRequest()
            val proof = sent.getHeader("DPoP")!!
            val claims = json(proof.split('.')[1])
            assertEquals("GET", claims["htm"]!!.jsonPrimitive.content)
            assertEquals(server.server.url("/rest/ping.view").toString(), claims["htu"]!!.jsonPrimitive.content)
        } finally {
            server.close()
        }
    }

    private fun json(part: String) =
        Json.parseToJsonElement(String(Base64.getUrlDecoder().decode(part), Charsets.UTF_8)).jsonObject

    private fun decode(part: String): ByteArray = Base64.getUrlDecoder().decode(part)
}
