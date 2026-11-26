package org.dancesonic.player.ui

import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.dancesonic.player.subsonic.LanPins
import org.dancesonic.player.subsonic.ServerSettings
import org.dancesonic.player.subsonic.SubsonicException
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** A private server's certificate is verified before it is used for anything secret (#126). */
class CertificateTrustTest {
    private val url = "https://192.168.1.5:4533/".toHttpUrl()
    private val observed = "sha256/observed"

    @Before
    fun setUp() = LanPins.clear()

    @After
    fun tearDown() = LanPins.clear()

    private fun trust(
        expected: String? = null,
        confirm: suspend (String) -> Boolean = { error("not asked") },
    ): String? = runBlocking { trustedPin(url, expected, confirm, read = { observed }) }

    @Test
    fun `a code's matching pin is trusted for the setup without asking`() {
        assertEquals(observed, trust(expected = observed))
        assertEquals(observed, LanPins.pinFor(url.host, url.port))
    }

    @Test
    fun `a mismatched code pin never becomes active`() {
        val error = assertThrows(SubsonicException::class.java) { trust(expected = "sha256/other") }

        assertEquals(PIN_MISMATCH, error.message)
        assertNull(LanPins.pinFor(url.host, url.port))
    }

    @Test
    fun `a typed address asks the user, and a trusted fingerprint becomes active`() {
        val shown = mutableListOf<String>()

        trust(confirm = {
            shown += it
            true
        })

        assertEquals(listOf(observed), shown)
        assertEquals(observed, LanPins.pinFor(url.host, url.port))
    }

    @Test
    fun `a refused fingerprint never becomes active`() {
        val error = assertThrows(SubsonicException::class.java) { trust(confirm = { false }) }

        assertEquals(CERTIFICATE_NOT_TRUSTED, error.message)
        assertNull(LanPins.pinFor(url.host, url.port))
    }

    @Test
    fun `the stored connection's own certificate is not asked about again`() {
        LanPins.remember(ServerSettings(url, "key", tlsPin = observed))

        assertEquals(observed, trust())
    }

    @Test
    fun `a changed certificate of the stored server is asked about`() {
        LanPins.remember(ServerSettings(url, "key", tlsPin = "sha256/old"))
        var asked = false

        trust(confirm = {
            asked = true
            true
        })

        assertTrue(asked)
    }

    @Test
    fun `a server that is not pinned needs no check`() {
        assertNull(runBlocking { trustedPin(url, null, { error("not asked") }, read = { null }) })
    }

    @Test
    fun `the check shows the fingerprint and returns the answer`() =
        runBlocking {
            val shown = mutableListOf<String?>()
            val check = CertificateCheck({ shown += it })

            val answer = async { check.await(observed) }
            yield()
            check.answer(false)

            assertFalse(answer.await())
            assertEquals(listOf(observed, null), shown)
        }

    @Test
    fun `an unanswered check expires`() {
        val check = CertificateCheck({}, waitMillis = 1)

        assertThrows(SubsonicException::class.java) { runBlocking { check.await(observed) } }
    }
}
