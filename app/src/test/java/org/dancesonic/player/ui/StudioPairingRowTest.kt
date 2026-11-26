package org.dancesonic.player.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import org.dancesonic.player.studio.pairing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A paired Studio in the list: transports, last connection, role, and renaming (#88). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class StudioPairingRowTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val now = 1_700_000_000_000L

    @Test
    fun `names are trimmed, cut and refused when blank`() {
        assertEquals("Laptop", studioName("  Laptop \n"))
        assertNull(studioName("   "))
        assertEquals(MAX_STUDIO_NAME, studioName("x".repeat(200))?.length)
    }

    @Test
    fun `the row lists the transports and when the Studio was last reached`() {
        val record = pairing("a").copy(lastConnectedAtMs = now - 2 * HOUR, lastTransport = "relay")

        assertEquals("Bluetooth, folksonic relay, Wi-Fi", transportsText(record))
        assertEquals("Last connected 2 hours ago over folksonic relay", lastConnectedText(record, now))
        assertEquals("Not connected yet", lastConnectedText(pairing("b"), now))
    }

    @Test
    fun `the last Studio played on is marked, and the playing one cannot be chosen again`() {
        composeRule.setContent {
            MaterialTheme {
                StudioPairingRow(pairing("a"), StudioRowRole.Playing, busy = false, actions = noActions(), nowMs = now)
                StudioPairingRow(pairing("b"), StudioRowRole.LastUsed, busy = false, actions = noActions(), nowMs = now)
            }
        }

        composeRule.onNodeWithText("Studio a (playing)").assertExists()
        composeRule.onNodeWithText("Studio b (last played on)").assertExists()
    }

    @Test
    fun `renaming asks for a name and passes it on`() {
        val names = mutableListOf<String>()
        composeRule.setContent {
            MaterialTheme {
                StudioPairingRow(
                    pairing("a"),
                    StudioRowRole.Other,
                    busy = false,
                    actions = noActions(onRename = { names += it }),
                    nowMs = now,
                )
            }
        }

        composeRule.onNodeWithText("Rename").performClick()
        composeRule.onNode(hasSetTextAction()).performTextReplacement("   ")
        composeRule.onNodeWithText("Save").assertIsNotEnabled()
        composeRule.onNode(hasSetTextAction()).performTextReplacement("Venue laptop")
        composeRule.onNodeWithText("Save").performClick()

        assertEquals(listOf("Venue laptop"), names)
    }

    private fun noActions(onRename: (String) -> Unit = {}) =
        StudioRowActions(onPlayOn = {}, onCheck = {}, onRename = onRename, onForget = {})

    private companion object {
        const val HOUR = 60L * 60 * 1000
    }
}
