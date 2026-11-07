package org.dancesonic.player.subsonic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class ServerUrlTest {
    @Test
    fun `a plain https address is kept and gets a trailing slash`() {
        assertEquals(
            "https://music.example.com/folksonic/",
            ServerUrl.parse("https://music.example.com/folksonic").toString(),
        )
    }

    @Test
    fun `a username in the address is rejected`() {
        assertRejected("https://alice@music.example.com/")
    }

    @Test
    fun `a username and password in the address are rejected`() {
        assertRejected("https://alice:hunter2@music.example.com/")
    }

    @Test
    fun `a password alone in the address is rejected`() {
        assertRejected("https://:hunter2@music.example.com/")
    }

    @Test
    fun `a query is rejected`() {
        assertRejected("https://music.example.com/?apiKey=abc")
    }

    @Test
    fun `a fragment is rejected`() {
        assertRejected("https://music.example.com/#token")
    }

    @Test
    fun `the error never repeats the credentials`() {
        val error =
            assertThrows(IllegalArgumentException::class.java) {
                ServerUrl.parse("https://alice:hunter2@music.example.com/")
            }
        assertFalse(error.message.orEmpty().contains("hunter2"))
        assertFalse(error.message.orEmpty().contains("alice"))
    }

    private fun assertRejected(input: String) {
        assertThrows(IllegalArgumentException::class.java) { ServerUrl.parse(input) }
    }
}
