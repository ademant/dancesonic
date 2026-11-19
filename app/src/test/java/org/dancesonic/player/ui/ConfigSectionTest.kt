package org.dancesonic.player.ui

import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ConfigSectionTest {
    @get:Rule
    val composeRule = createComposeRule()

    private var attention by mutableStateOf(false)

    private fun show() {
        composeRule.setContent {
            ConfigSection("Playback", attention = attention) {
                Text("ReplayGain option")
            }
        }
    }

    @Test
    fun `a section starts collapsed, with its title but without its settings`() {
        show()

        composeRule.onAllNodesWithText("Playback").assertCountEquals(1)
        composeRule.onAllNodesWithText("ReplayGain option").assertCountEquals(0)
    }

    @Test
    fun `tapping the title opens the section, and tapping again closes it`() {
        show()

        composeRule.onNodeWithText("Playback").performClick()
        composeRule.onAllNodesWithText("ReplayGain option").assertCountEquals(1)

        composeRule.onNodeWithText("Playback").performClick()
        composeRule.onAllNodesWithText("ReplayGain option").assertCountEquals(0)
    }

    @Test
    fun `attention opens a section that is collapsed`() {
        show()
        composeRule.onAllNodesWithText("ReplayGain option").assertCountEquals(0)

        attention = true
        composeRule.waitForIdle()

        composeRule.onAllNodesWithText("ReplayGain option").assertCountEquals(1)
    }
}
