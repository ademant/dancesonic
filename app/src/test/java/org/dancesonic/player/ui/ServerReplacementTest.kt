package org.dancesonic.player.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.dancesonic.player.subsonic.ServerSettings
import org.dancesonic.player.subsonic.SubsonicException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A scanned code that names another server replaces the stored connection only after the user agrees (#135). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ServerReplacementTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val stored = ServerSettings("https://music.example.com/".toHttpUrl(), "key-123")

    @Test
    fun `the same server, or no stored server, needs no confirmation`() {
        assertNull(replacementOf(stored, "https://music.example.com/".toHttpUrl()))
        assertNull(replacementOf(null, "https://other.example.com/".toHttpUrl()))
    }

    @Test
    fun `another server shows both hosts, with a port that is not the default`() {
        assertEquals(
            ServerReplacement("music.example.com", "other.example.com"),
            replacementOf(stored, "https://other.example.com/".toHttpUrl()),
        )
        assertEquals(
            ServerReplacement("music.example.com", "music.example.com:8443"),
            replacementOf(stored, "https://music.example.com:8443/".toHttpUrl()),
        )
    }

    @Test
    fun `keeping the current connection stops the replacement`() =
        runBlocking {
            var shown: ServerReplacement? = null
            val check = ReplaceCheck(show = { shown = it })

            val result = async { runCatching { check.confirm(stored, "https://other.example.com/".toHttpUrl()) } }
            while (shown == null) yield()
            check.answer(false)

            val error = result.await().exceptionOrNull()
            assertTrue(error is SubsonicException)
            assertEquals(REPLACE_DECLINED, error?.message)
            assertNull(shown)
        }

    @Test
    fun `agreeing lets the scanned server replace the stored one`() =
        runBlocking {
            var shown: ServerReplacement? = null
            val check = ReplaceCheck(show = { shown = it })

            val result = async { check.confirm(stored, "https://other.example.com/".toHttpUrl()) }
            while (shown == null) yield()
            check.answer(true)

            result.await()
            assertNull(shown)
        }

    @Test
    fun `no answer in time keeps the current connection`() {
        val check = ReplaceCheck(show = {}, waitMillis = 10L)

        assertThrows(SubsonicException::class.java) {
            runBlocking { check.confirm(stored, "https://other.example.com/".toHttpUrl()) }
        }
    }

    @Test
    fun `the dialog names both hosts and answers each button`() {
        val answers = mutableListOf<Boolean>()
        composeRule.setContent {
            MaterialTheme {
                ReplaceConfirmation(ServerReplacement("music.example.com", "other.example.com")) { answers += it }
            }
        }

        composeRule.onNodeWithText("other.example.com", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("music.example.com", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("Keep current").performClick()
        composeRule.onNodeWithText("Replace").performClick()

        assertEquals(listOf(false, true), answers)
    }

    @Test
    fun `nothing is shown while no replacement waits`() {
        composeRule.setContent { MaterialTheme { ReplaceConfirmation(null) { } } }

        composeRule.onNodeWithText("Replace the connection?").assertDoesNotExist()
    }
}
