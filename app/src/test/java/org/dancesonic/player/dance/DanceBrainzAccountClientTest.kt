package org.dancesonic.player.dance

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import org.dancesonic.player.testsupport.SoftwareDeviceSigner
import org.dancesonic.player.testsupport.TestSubsonicServer
import org.dancesonic.player.testsupport.tokenCredential
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DanceBrainzAccountClientTest {
    @Test
    fun `pairing sends the code, the device name and the public key, and no secret comes back`() {
        TestSubsonicServer().use { server ->
            server.server.enqueue(MockResponse().setResponseCode(202).setBody("""{"status":"pending"}"""))
            val jwk = SoftwareDeviceSigner().publicJwk()

            runBlocking {
                DanceBrainzAccountClient(
                    server.server.url("/"),
                    server.plain,
                ).pairDevice("dbp_abcdefghijklmnop1234", "DanceSonic Pixel", jwk)
            }

            val request = server.server.takeRequest()
            assertEquals("POST", request.method)
            assertEquals("/api/v1/pairings/complete", request.requestUrl?.encodedPath)
            val body = request.body.readUtf8()
            assertTrue(body.contains("\"token\":\"dbp_abcdefghijklmnop1234\""))
            assertTrue(body.contains("\"device\":\"DanceSonic Pixel\""))
            assertTrue(body.contains("\"x\":\"${jwk.x}\""))
            assertNull(request.getHeader("Authorization"))
        }
    }

    @Test
    fun `a rejected code is reported without the code`() {
        TestSubsonicServer().use { server ->
            server.server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"pairing_invalid"}"""))

            val error =
                assertThrows(DanceBrainzException::class.java) {
                    runBlocking {
                        DanceBrainzAccountClient(server.server.url("/"), server.plain)
                            .pairDevice("dbp_abcdefghijklmnop1234", "DanceSonic", SoftwareDeviceSigner().publicJwk())
                    }
                }

            assertEquals(AWAITED_INVALID_CODE, error.message)
            assertFalse(error.message.orEmpty().contains("dbp_"))
        }
    }

    @Test
    fun `the wait ends when the owner approves the device, and each poll is signed for the device`() {
        TestSubsonicServer().use { server ->
            server.server.enqueue(json("""{"error":"pairing_pending"}""").setResponseCode(401))
            server.server.enqueue(MockResponse().setResponseCode(200))

            runBlocking {
                deviceAccount(
                    server,
                ).awaitApproval(
                    DanceBrainzCredential.Device("dancesonic.device.abc"),
                    timeoutMillis = 60_000,
                    pollMillis = 1,
                )
            }

            assertEquals(2, server.server.requestCount)
            repeat(2) {
                val request = server.server.takeRequest()
                assertEquals("/api/v1/devices/me", request.requestUrl?.encodedPath)
                assertNotNull(request.getHeader("DPoP"))
                assertNull(request.getHeader("X-DanceSonic-Device"))
                assertNull(request.getHeader("Authorization"))
            }
        }
    }

    @Test
    fun `a removed device is told to pair again`() {
        TestSubsonicServer().use { server ->
            server.server.enqueue(json("""{"error":"device_unknown"}""").setResponseCode(401))

            val error =
                assertThrows(DanceBrainzException::class.java) {
                    runBlocking {
                        deviceAccount(
                            server,
                        ).awaitApproval(
                            DanceBrainzCredential.Device("dancesonic.device.abc"),
                            timeoutMillis = 60_000,
                            pollMillis = 1,
                        )
                    }
                }
            assertTrue(error.message.orEmpty().contains("Pair it again"))
        }
    }

    @Test
    fun `a replayed proof is reported as a repeat`() {
        TestSubsonicServer().use { server ->
            server.server.enqueue(json("""{"error":"proof_replayed"}""").setResponseCode(401))

            val error =
                assertThrows(DanceBrainzException::class.java) {
                    runBlocking {
                        deviceAccount(
                            server,
                        ).awaitApproval(
                            DanceBrainzCredential.Device("dancesonic.device.abc"),
                            timeoutMillis = 60_000,
                            pollMillis = 1,
                        )
                    }
                }
            assertTrue(error.message.orEmpty().contains("repeated request"))
        }
    }

    @Test
    fun `a device never approved gives up at its deadline`() {
        TestSubsonicServer().use { server ->
            server.server.enqueue(json("""{"error":"pairing_pending"}""").setResponseCode(401))

            val error =
                assertThrows(DanceBrainzException::class.java) {
                    runBlocking {
                        deviceAccount(
                            server,
                        ).awaitApproval(
                            DanceBrainzCredential.Device("dancesonic.device.abc"),
                            timeoutMillis = 0,
                            pollMillis = 1,
                        )
                    }
                }
            assertTrue(error.message.orEmpty().contains("did not arrive in time"))
        }
    }

    @Test
    fun `the check sends the token as a bearer header to the login-only read`() {
        TestSubsonicServer().use { server ->
            server.server.enqueue(json("""{"id":7,"role":"user"}"""))
            val client = DanceBrainzAccountClient(server.server.url("/"), server.plain)

            runBlocking { client.check(tokenCredential("dbz_checked_token_1234567890")) }

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
                runBlocking { client.check(tokenCredential("dbz_revoked_token_1234567890")) }
            }
        }
    }

    @Test
    fun `revoke deletes the token by id with its own bearer token`() {
        TestSubsonicServer().use { server ->
            server.server.enqueue(MockResponse().setResponseCode(204))
            val client = DanceBrainzAccountClient(server.server.url("/"), server.plain)

            runBlocking { client.revoke(tokenCredential("dbz_device_token_1234567890"), 42L) }

            val request = server.server.takeRequest()
            assertEquals("DELETE", request.method)
            assertEquals("/api/v1/tokens/42", request.requestUrl?.encodedPath)
            assertEquals("Bearer dbz_device_token_1234567890", request.getHeader("Authorization"))
        }
    }

    /** A client that signs requests for a device with an in-memory key, as the Keystore would. */
    private fun deviceAccount(server: TestSubsonicServer): DanceBrainzAccountClient {
        val http =
            server.plain.newBuilder().addInterceptor(
                DanceBrainzDeviceInterceptor({ SoftwareDeviceSigner() }),
            ).build()
        return DanceBrainzAccountClient(server.server.url("/"), http)
    }

    private fun json(body: String) = MockResponse().addHeader("Content-Type", "application/json").setBody(body)

    private companion object {
        const val AWAITED_INVALID_CODE =
            "This pairing code is invalid or expired. Create a new one on the account page."
    }
}
