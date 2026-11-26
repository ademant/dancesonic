package org.dancesonic.player.subsonic

import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.dancesonic.player.testsupport.TestSubsonicServer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.InetAddress

/** Android 17's local network permission, as the app's HTTP clients apply it (#124). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LocalNetworkAccessTest {
    @After
    fun tearDown() {
        LocalNetworkAccess.install { true }
    }

    @Test
    fun `the permission is asked for only from Android 17`() {
        assertArrayEquals(emptyArray<String>(), LocalNetworkAccess.required(sdk = 36))
        assertArrayEquals(
            arrayOf("android.permission.ACCESS_LOCAL_NETWORK"),
            LocalNetworkAccess.required(sdk = 37),
        )
    }

    @Test
    fun `private, link-local and unique-local addresses are local, loopback and public ones are not`() {
        listOf("192.168.1.5", "10.0.0.2", "172.16.4.1", "169.254.1.1", "fe80::1", "fd12::1").forEach {
            assertTrue(it, LocalNetworkAccess.isLocal(InetAddress.getByName(it)))
        }
        listOf("127.0.0.1", "::1", "8.8.8.8", "2001:4860::8888").forEach {
            assertFalse(it, LocalNetworkAccess.isLocal(InetAddress.getByName(it)))
        }
        assertTrue(LocalNetworkAccess.needsPermission("https://192.168.1.5/".toHttpUrl()))
        assertFalse(LocalNetworkAccess.needsPermission("https://localhost/".toHttpUrl()))
        assertFalse(LocalNetworkAccess.needsPermission("https://music.example.com/".toHttpUrl()))
    }

    @Test
    fun `a request to a local server fails at once and sends nothing while the permission is missing`() {
        LocalNetworkAccess.install { false }
        val settings = ServerSettings("https://192.168.1.5:4533/".toHttpUrl(), "secret-key")
        val request = Request.Builder().url("https://192.168.1.5:4533/rest/ping.view").build()

        val error =
            assertThrows(LocalNetworkBlockedException::class.java) {
                SubsonicHttp.okHttpClient { settings }.newCall(request).execute()
            }

        assertEquals(LocalNetworkBlockedException.MESSAGE, error.message)
    }

    @Test
    fun `the Subsonic client reports the missing permission instead of a timeout`() {
        LocalNetworkAccess.install { false }
        val client = SubsonicClient(ServerSettings("https://192.168.1.5:4533/".toHttpUrl(), "secret-key"))

        val error = assertThrows(SubsonicException::class.java) { runBlocking { client.checkConnection() } }

        assertEquals(LocalNetworkBlockedException.MESSAGE, error.message)
    }

    @Test
    fun `no certificate is read from a local server while the permission is missing`() {
        LocalNetworkAccess.install { false }

        assertThrows(LocalNetworkBlockedException::class.java) {
            runBlocking { LanTls.connectPin("https://192.168.1.5:4533/".toHttpUrl()) }
        }
    }

    @Test
    fun `this device and public servers do not need the permission`() {
        LocalNetworkAccess.install { false }
        TestSubsonicServer().use { server ->
            server.server.enqueue(okhttp3.mockwebserver.MockResponse().setBody("{}"))
            val plain = SubsonicHttp.okHttpClient { null }
            // localhost: the TLS check fails later, but the local network check lets it through.
            val error = runCatching { plain.newCall(Request.Builder().url(server.server.url("/")).build()).execute() }
            assertFalse(error.exceptionOrNull() is LocalNetworkBlockedException)
        }
    }
}
