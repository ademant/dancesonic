package org.dancesonic.player.dance

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import org.dancesonic.player.testsupport.TestSubsonicServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class DanceBrainzAccountClientTest {
    @Test
    fun `redeeming a code sends the code and device and returns the device token`() {
        TestSubsonicServer().use { server ->
            server.server.enqueue(json("""{"token":"dbz_device_token_1234567890","id":42,"server":"https://x/"}"""))
            val client = DanceBrainzAccountClient(server.server.url("/"), server.plain)

            val credential = runBlocking { client.redeem("dbp_abcdefghijklmnop1234", "DanceSonic Pixel") }

            val request = server.server.takeRequest()
            assertEquals("POST", request.method)
            assertEquals("/api/v1/pairings/complete", request.requestUrl?.encodedPath)
            val body = request.body.readUtf8()
            assertEquals(true, body.contains("\"token\":\"dbp_abcdefghijklmnop1234\""))
            assertEquals(true, body.contains("\"device\":\"DanceSonic Pixel\""))
            assertEquals("dbz_device_token_1234567890", credential.token)
            assertEquals(42L, credential.tokenId)
        }
    }

    @Test
    fun `a rejected code is reported without the code`() {
        TestSubsonicServer().use { server ->
            server.server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"pairing_invalid"}"""))
            val client = DanceBrainzAccountClient(server.server.url("/"), server.plain)

            val error =
                assertThrows(DanceBrainzException::class.java) {
                    runBlocking { client.redeem("dbp_abcdefghijklmnop1234", "DanceSonic") }
                }

            assertEquals(true, error.message?.contains("not accepted") == true)
            assertFalse(error.message.orEmpty().contains("dbp_"))
        }
    }

    @Test
    fun `the check sends the token as a bearer header to the login-only read`() {
        TestSubsonicServer().use { server ->
            server.server.enqueue(json("""{"id":7,"role":"user"}"""))
            val client = DanceBrainzAccountClient(server.server.url("/"), server.plain)

            runBlocking { client.check("dbz_checked_token_1234567890") }

            val request = server.server.takeRequest()
            assertEquals("GET", request.method)
            assertEquals("/api/v1/me", request.requestUrl?.encodedPath)
            assertEquals("Bearer dbz_checked_token_1234567890", request.getHeader("Authorization"))
        }
    }

    @Test
    fun `a revoked token fails the check with a message for the user`() {
        TestSubsonicServer().use { server ->
            server.server.enqueue(MockResponse().setResponseCode(401))
            val client = DanceBrainzAccountClient(server.server.url("/"), server.plain)

            assertThrows(DanceBrainzException::class.java) {
                runBlocking { client.check("dbz_revoked_token_1234567890") }
            }
        }
    }

    @Test
    fun `revoke deletes the token by id with its own bearer token`() {
        TestSubsonicServer().use { server ->
            server.server.enqueue(MockResponse().setResponseCode(204))
            val client = DanceBrainzAccountClient(server.server.url("/"), server.plain)

            runBlocking { client.revoke("dbz_device_token_1234567890", 42L) }

            val request = server.server.takeRequest()
            assertEquals("DELETE", request.method)
            assertEquals("/api/v1/tokens/42", request.requestUrl?.encodedPath)
            assertEquals("Bearer dbz_device_token_1234567890", request.getHeader("Authorization"))
        }
    }

    private fun json(body: String) = MockResponse().addHeader("Content-Type", "application/json").setBody(body)
}
