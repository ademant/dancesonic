package org.dancesonic.player.account

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.HttpUrl
import okhttp3.mockwebserver.MockResponse
import org.dancesonic.player.subsonic.DeviceProofInterceptor
import org.dancesonic.player.subsonic.ServerAuth
import org.dancesonic.player.subsonic.ServerSettings
import org.dancesonic.player.testsupport.SoftwareDeviceSigner
import org.dancesonic.player.testsupport.TestSubsonicServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AccountClientTest {
    private lateinit var fake: TestSubsonicServer
    private lateinit var account: AccountClient
    private lateinit var base: HttpUrl

    @Before
    fun setUp() {
        fake = TestSubsonicServer()
        account = AccountClient(fake.http)
        base = fake.settings.baseUrl
    }

    @After
    fun tearDown() {
        fake.close()
    }

    @Test
    fun `login posts the credentials and returns the session token`() =
        runBlocking<Unit> {
            fake.server.enqueue(json("""{"token":"sess-abc","username":"ada","role":"user"}"""))

            val token = account.login(base, "ada", "s3cret")

            assertEquals("sess-abc", token)
            val request = fake.server.takeRequest()
            assertEquals("POST", request.method)
            assertEquals("/api/login", request.requestUrl!!.encodedPath)
            assertTrue(request.body.readUtf8().contains("\"password\":\"s3cret\""))
            assertEquals(null, request.getHeader("Authorization"))
        }

    @Test
    fun `wrong password gives a readable message`() =
        runBlocking<Unit> {
            fake.server.enqueue(
                MockResponse().setResponseCode(401).setBody("""{"error":"wrong username or password"}"""),
            )

            val error =
                assertThrows(AccountException::class.java) {
                    runBlocking { account.login(base, "ada", "nope") }
                }
            assertEquals("Wrong username or password.", error.message)
        }

    @Test
    fun `api key is created with the session as Bearer token and never expires`() =
        runBlocking<Unit> {
            fake.server.enqueue(json("""{"apiKey":"key-xyz","label":"DanceSonic Android","expiresAt":null}"""))

            val key = account.createApiKey(base, "sess-abc")

            assertEquals("key-xyz", key)
            val request = fake.server.takeRequest()
            assertEquals("/api/keys", request.requestUrl!!.encodedPath)
            assertEquals("Bearer sess-abc", request.getHeader("Authorization"))
            val body = request.body.readUtf8()
            assertTrue(body.contains("\"label\":\"DanceSonic Android\""))
            assertTrue(body.contains("\"expires_days\":0"))
        }

    @Test
    fun `logout sends the session token`() =
        runBlocking<Unit> {
            fake.server.enqueue(json("""{"status":"ok"}"""))

            account.logout(base, "sess-abc")

            val request = fake.server.takeRequest()
            assertEquals("/api/logout", request.requestUrl!!.encodedPath)
            assertEquals("Bearer sess-abc", request.getHeader("Authorization"))
        }

    @Test
    fun `failed logout is reported as an account error`() =
        runBlocking<Unit> {
            fake.server.enqueue(MockResponse().setResponseCode(500))

            assertThrows(AccountException::class.java) {
                runBlocking { account.logout(base, "sess-abc") }
            }
        }

    @Test
    fun `the pairing sends the token and the public key, and waits for approval`() =
        runBlocking<Unit> {
            fake.server.enqueue(MockResponse().setResponseCode(202).setBody("""{"status":"pending"}"""))
            val request = PairingRequest(server = base, token = "tsp_abcdefgh12345")
            val jwk = SoftwareDeviceSigner().publicJwk()

            account.startDevicePairing(request, "Pixel", jwk)

            val sent = fake.server.takeRequest()
            assertEquals("/api/pair/complete", sent.requestUrl!!.encodedPath)
            val body = sent.body.readUtf8()
            assertTrue(body.contains("\"token\":\"tsp_abcdefgh12345\""))
            assertTrue(body.contains("\"jwk\""))
            assertTrue(body.contains("\"x\":\"${jwk.x}\""))
            assertNull(sent.getHeader("DPoP"))
        }

    @Test
    fun `an expired pairing token gives the account page hint`() =
        runBlocking<Unit> {
            fake.server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"pairing_expired"}"""))

            val error =
                assertThrows(AccountException::class.java) {
                    runBlocking {
                        account.startDevicePairing(
                            PairingRequest(base, "tsp_abcdefgh12345"),
                            "Pixel",
                            SoftwareDeviceSigner().publicJwk(),
                        )
                    }
                }
            assertEquals(AccountClient.INVALID_PAIRING_CODE, error.message)
        }

    @Test
    fun `the wait ends when the owner approves the device`() =
        runBlocking<Unit> {
            fake.server.enqueue(json("""{"error":"pairing_pending"}""").setResponseCode(401))
            fake.server.enqueue(json("""{"error":"pairing_pending"}""").setResponseCode(401))
            fake.server.enqueue(json("""{"id":7,"name":"Pixel"}"""))

            deviceAccount().awaitDeviceApproval(base, timeoutMillis = 60_000, pollMillis = 1)

            assertEquals(3, fake.server.requestCount)
        }

    @Test
    fun `every poll carries a proof for the device's own request`() =
        runBlocking<Unit> {
            fake.server.enqueue(json("""{"id":7,"name":"Pixel"}"""))

            deviceAccount().awaitDeviceApproval(base, timeoutMillis = 60_000, pollMillis = 1)

            val sent = fake.server.takeRequest()
            assertEquals("/api/device/me", sent.requestUrl!!.encodedPath)
            assertNotNull(sent.getHeader("DPoP"))
            assertNull(sent.requestUrl!!.queryParameter("apiKey"))
        }

    @Test
    fun `a removed device is told to pair again`() =
        runBlocking<Unit> {
            fake.server.enqueue(json("""{"error":"device_unknown"}""").setResponseCode(401))

            val error =
                assertThrows(AccountException::class.java) {
                    runBlocking { deviceAccount().awaitDeviceApproval(base, timeoutMillis = 60_000, pollMillis = 1) }
                }
            assertTrue(error.message.orEmpty().contains("Pair it again"))
        }

    @Test
    fun `a pairing never approved gives up at its deadline`() =
        runBlocking<Unit> {
            fake.server.enqueue(json("""{"error":"pairing_pending"}""").setResponseCode(401))

            val error =
                assertThrows(AccountException::class.java) {
                    runBlocking { deviceAccount().awaitDeviceApproval(base, timeoutMillis = 0, pollMillis = 1) }
                }
            assertTrue(error.message.orEmpty().contains("did not arrive in time"))
        }

    @Test
    fun `the answer asks for the code to be confirmed, and an older server does not`() =
        runBlocking<Unit> {
            fake.server.enqueue(json("""{"status":"pending","pendingId":12,"confirm":true}""").setResponseCode(202))
            fake.server.enqueue(json("""{"status":"pending"}""").setResponseCode(202))
            val jwk = SoftwareDeviceSigner().publicJwk()
            val request = PairingRequest(base, "tsp_abcdefgh12345")

            val withCode = account.startDevicePairing(request, "Pixel", jwk)
            val older = account.startDevicePairing(request, "Pixel", jwk)

            assertTrue(withCode.confirm)
            assertEquals("12", withCode.pendingId.toString())
            assertFalse(older.confirm)
        }

    @Test
    fun `the confirmation carries the pending id, the answer and the device's proof`() =
        runBlocking<Unit> {
            fake.server.enqueue(MockResponse().setResponseCode(200))

            deviceAccount().confirmDevicePairing(base, JsonPrimitive(12), PairingDecision.MATCH)

            val sent = fake.server.takeRequest()
            assertEquals("/api/pair/confirm", sent.requestUrl!!.encodedPath)
            val body = sent.body.readUtf8()
            assertTrue(body.contains("\"pairing\":12"))
            assertTrue(body.contains("\"result\":\"match\""))
            assertNotNull(sent.getHeader("DPoP"))
        }

    @Test
    fun `a code used by another device gives its own message when the phone confirms`() =
        runBlocking<Unit> {
            fake.server.enqueue(json("""{"error":"pairing_token_used"}""").setResponseCode(401))

            val error =
                assertThrows(AccountException::class.java) {
                    runBlocking {
                        deviceAccount().confirmDevicePairing(base, JsonPrimitive(12), PairingDecision.MATCH)
                    }
                }
            assertEquals(AccountClient.TOKEN_ALREADY_USED, error.message)
        }

    @Test
    fun `a token already used at the start gives the same message`() =
        runBlocking<Unit> {
            fake.server.enqueue(json("""{"error":"pairing_token_used"}""").setResponseCode(401))

            val error =
                assertThrows(AccountException::class.java) {
                    runBlocking {
                        account.startDevicePairing(
                            PairingRequest(base, "tsp_abcdefgh12345"),
                            "Pixel",
                            SoftwareDeviceSigner().publicJwk(),
                        )
                    }
                }
            assertEquals(AccountClient.TOKEN_ALREADY_USED, error.message)
        }

    @Test
    fun `a pairing the owner rejected ends the wait with that reason`() =
        runBlocking<Unit> {
            fake.server.enqueue(json("""{"error":"pairing_rejected"}""").setResponseCode(401))

            val error =
                assertThrows(AccountException::class.java) {
                    runBlocking { deviceAccount().awaitDeviceApproval(base, timeoutMillis = 60_000, pollMillis = 1) }
                }
            assertEquals(AccountClient.PAIRING_REJECTED, error.message)
        }

    /** The account client for a paired device: the proof is signed with an in-memory key and sent over the test TLS. */
    private fun deviceAccount(): AccountClient {
        val settings = ServerSettings(base, ServerAuth.Device("test-device"))
        val signer = SoftwareDeviceSigner()
        val http = fake.plain.newBuilder().addInterceptor(DeviceProofInterceptor({ settings }, { signer })).build()
        return AccountClient(http)
    }

    @Test
    fun `unexpected status is reported with its code`() =
        runBlocking<Unit> {
            fake.server.enqueue(MockResponse().setResponseCode(503))

            val error =
                assertThrows(AccountException::class.java) {
                    runBlocking { account.login(base, "ada", "pw") }
                }
            assertEquals("The server returned HTTP 503.", error.message)
        }

    @Test
    fun `own key is revoked by id after matching its prefix`() =
        runBlocking<Unit> {
            fake.server.enqueue(
                json(
                    """{"keys":[{"id":7,"prefix":"tsk_other","label":"x"},
                    {"id":42,"prefix":"tsk_mine","label":"DanceSonic Android"}]}""",
                ),
            )
            fake.server.enqueue(json("""{"status":"revoked"}"""))

            val revoked = account.revokeOwnKey(base, "tsk_mine_full_secret")

            assertTrue(revoked)
            val list = fake.server.takeRequest()
            assertEquals("/api/keys", list.requestUrl!!.encodedPath)
            assertEquals("tsk_mine_full_secret", list.requestUrl!!.queryParameter("apiKey"))
            val delete = fake.server.takeRequest()
            assertEquals("DELETE", delete.method)
            assertEquals("/api/keys/42", delete.requestUrl!!.encodedPath)
        }

    @Test
    fun `nothing is revoked when the key is no longer listed`() =
        runBlocking<Unit> {
            fake.server.enqueue(json("""{"keys":[{"id":7,"prefix":"tsk_other"}]}"""))

            assertFalse(account.revokeOwnKey(base, "tsk_mine_full_secret"))
            assertEquals(1, fake.server.requestCount)
        }

    @Test
    fun `ambiguous prefix revokes nothing`() {
        val keys = listOf(KeyInfo(1, "tsk_same"), KeyInfo(2, "tsk_same"))
        assertEquals(null, AccountClient.matchOwnKeyId(keys, "tsk_same_secret"))
    }

    @Test
    fun `empty prefix never matches`() {
        assertEquals(null, AccountClient.matchOwnKeyId(listOf(KeyInfo(1, "")), "anything"))
    }

    @Test
    fun `request objects never print secrets`() {
        val login = LoginRequest("ada", "s3cret").toString()
        val pair = DevicePairRequest("tsp_abcdefgh12345", "phone", SoftwareDeviceSigner().publicJwk()).toString()
        val pairing = PairingRequest(base, "tsp_abcdefgh12345").toString()

        listOf(login, pair, pairing).forEach { text ->
            assertFalse(text.contains("s3cret"))
            assertFalse(text.contains("tsp_abcdefgh12345"))
        }
    }

    private fun json(body: String) =
        MockResponse()
            .addHeader("Content-Type", "application/json")
            .setBody(body)
}
