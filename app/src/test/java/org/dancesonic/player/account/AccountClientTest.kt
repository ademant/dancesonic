package org.dancesonic.player.account

import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl
import okhttp3.mockwebserver.MockResponse
import org.dancesonic.player.testsupport.TestSubsonicServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun `login posts the credentials and returns the session token`() = runBlocking<Unit> {
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
    fun `wrong password gives a readable message`() = runBlocking<Unit> {
        fake.server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"wrong username or password"}"""))

        val error = assertThrows(AccountException::class.java) {
            runBlocking { account.login(base, "ada", "nope") }
        }
        assertEquals("Wrong username or password.", error.message)
    }

    @Test
    fun `api key is created with the session as Bearer token and never expires`() = runBlocking<Unit> {
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
    fun `logout sends the session token`() = runBlocking<Unit> {
        fake.server.enqueue(json("""{"status":"ok"}"""))

        account.logout(base, "sess-abc")

        val request = fake.server.takeRequest()
        assertEquals("/api/logout", request.requestUrl!!.encodedPath)
        assertEquals("Bearer sess-abc", request.getHeader("Authorization"))
    }

    @Test
    fun `failed logout is reported as an account error`() = runBlocking<Unit> {
        fake.server.enqueue(MockResponse().setResponseCode(500))

        assertThrows(AccountException::class.java) {
            runBlocking { account.logout(base, "sess-abc") }
        }
    }

    @Test
    fun `pairing token is redeemed for an API key`() = runBlocking<Unit> {
        fake.server.enqueue(json("""{"apiKey":"key-from-qr","username":"ada","server":"${base}"}"""))
        val request = PairingRequest(server = base, token = "tsp_abcdefgh12345")

        val key = account.redeemPairing(request)

        assertEquals("key-from-qr", key)
        val sent = fake.server.takeRequest()
        assertEquals("/api/pair/complete", sent.requestUrl!!.encodedPath)
        val body = sent.body.readUtf8()
        assertTrue(body.contains("\"token\":\"tsp_abcdefgh12345\""))
        assertTrue(body.contains("\"device\":\"DanceSonic Android\""))
    }

    @Test
    fun `expired pairing token gives the account page hint`() = runBlocking<Unit> {
        fake.server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"invalid or expired pairing token"}"""))

        val error = assertThrows(AccountException::class.java) {
            runBlocking { account.redeemPairing(PairingRequest(base, "tsp_abcdefgh12345")) }
        }
        assertEquals(AccountClient.INVALID_PAIRING_CODE, error.message)
    }

    @Test
    fun `unexpected status is reported with its code`() = runBlocking<Unit> {
        fake.server.enqueue(MockResponse().setResponseCode(503))

        val error = assertThrows(AccountException::class.java) {
            runBlocking { account.login(base, "ada", "pw") }
        }
        assertEquals("The server returned HTTP 503.", error.message)
    }

    @Test
    fun `request objects never print secrets`() {
        val login = LoginRequest("ada", "s3cret").toString()
        val pair = PairCompleteRequest("tsp_abcdefgh12345", "phone").toString()
        val pairing = PairingRequest(base, "tsp_abcdefgh12345").toString()

        listOf(login, pair, pairing).forEach { text ->
            assertFalse(text.contains("s3cret"))
            assertFalse(text.contains("tsp_abcdefgh12345"))
        }
    }

    private fun json(body: String) = MockResponse()
        .addHeader("Content-Type", "application/json")
        .setBody(body)
}
