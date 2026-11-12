package org.dancesonic.player.dance

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import org.dancesonic.player.testsupport.SoftwareDeviceSigner
import org.dancesonic.player.testsupport.TestSubsonicServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class DanceBrainzDeviceAuthTest {
    @Test
    fun `a token is sent as a bearer header and a device as its key name`() {
        assertEquals(
            mapOf("Authorization" to "Bearer dbz_x_token_1234567890"),
            DanceBrainzCredential.Token("dbz_x_token_1234567890", null).authHeaders(),
        )
        assertEquals(
            mapOf(DEVICE_KEY_HEADER to "dancesonic.device.abc"),
            DanceBrainzCredential.Device("dancesonic.device.abc").authHeaders(),
        )
        assertEquals(emptyMap<String, String>(), (null as DanceBrainzCredential?).authHeaders())
    }

    @Test
    fun `a signed request carries a DPoP proof for its method and URL without the query`() {
        TestSubsonicServer().use { server ->
            server.server.enqueue(MockResponse().setResponseCode(200))
            val http =
                server.plain.newBuilder().addInterceptor(
                    DanceBrainzDeviceInterceptor({
                        SoftwareDeviceSigner()
                    }, { 1_700_000_000L }),
                ).build()
            val url = server.server.url("/api/v1/devices/me?probe=1")

            http.newCall(
                okhttp3.Request.Builder().url(url).get().header(DEVICE_KEY_HEADER, "dancesonic.device.abc").build(),
            ).execute().close()

            val request = server.server.takeRequest()
            val proof = request.getHeader("DPoP")!!
            assertNull(request.getHeader(DEVICE_KEY_HEADER))
            val claims = String(Base64.getUrlDecoder().decode(proof.split('.')[1]))
            assertTrue(claims.contains("\"htm\":\"GET\""))
            assertTrue(claims.contains("\"htu\":\"${server.server.url("/api/v1/devices/me")}\""))
            assertTrue(claims.contains("\"iat\":1700000000"))
        }
    }

    @Test
    fun `a request without a device header is sent unchanged`() {
        TestSubsonicServer().use { server ->
            server.server.enqueue(MockResponse().setResponseCode(200))
            val http =
                server.plain.newBuilder().addInterceptor(
                    DanceBrainzDeviceInterceptor({ SoftwareDeviceSigner() }),
                ).build()

            http.newCall(
                okhttp3.Request.Builder().url(server.server.url("/api/v1/recordings/x")).get().build(),
            ).execute().close()

            val request = server.server.takeRequest()
            assertNull(request.getHeader("DPoP"))
        }
    }

    @Test
    fun `a paired device edits a rating with a proof and no bearer token`() {
        TestSubsonicServer().use { server ->
            server.server.enqueue(MockResponse().setResponseCode(204))
            val http =
                server.plain.newBuilder().addInterceptor(
                    DanceBrainzDeviceInterceptor({ SoftwareDeviceSigner() }),
                ).build()
            val client = DanceBrainzRatingsClient(server.server.url("/"), http)

            runBlocking {
                client.setShoes(
                    "5ec2d0c5-03e3-4d8b-9ad2-0c11a6b7a5d1",
                    4,
                    DanceBrainzCredential.Device("dancesonic.device.abc"),
                )
            }

            val request = server.server.takeRequest()
            assertEquals("PUT", request.method)
            assertEquals(
                "/api/v1/recordings/5ec2d0c5-03e3-4d8b-9ad2-0c11a6b7a5d1/shoes",
                request.requestUrl?.encodedPath,
            )
            assertNotNull(request.getHeader("DPoP"))
            assertNull(request.getHeader("Authorization"))
            assertNull(request.getHeader(DEVICE_KEY_HEADER))
        }
    }
}
