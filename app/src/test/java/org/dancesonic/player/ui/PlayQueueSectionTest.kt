package org.dancesonic.player.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.dancesonic.player.playback.PlayQueue
import org.dancesonic.player.playback.PlayQueueEntry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PlayQueueSectionTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `the queue shows played and upcoming songs and tapping one selects it`() {
        val selected = mutableListOf<Int>()
        val queue =
            PlayQueue(
                entries =
                    listOf(
                        PlayQueueEntry("s-1", "First", "Ada"),
                        PlayQueueEntry("s-2", "Second", "Ada"),
                        PlayQueueEntry("s-3", "Third", "Bea"),
                    ),
                currentIndex = 1,
            )
        composeRule.setContent { MaterialTheme { PlayQueueSection(queue, onPlayAt = { selected.add(it) }) } }

        composeRule.onNodeWithText("Played · Ada").assertIsDisplayed()
        composeRule.onNodeWithText("Now playing · Ada").assertIsDisplayed()
        composeRule.onNodeWithText("Up next · Bea").performClick()

        assertEquals(listOf(2), selected)
    }

    @Test
    fun `an empty queue explains how to start one`() {
        composeRule.setContent {
            MaterialTheme { PlayQueueSection(PlayQueue(), onPlayAt = {}) }
        }

        composeRule.onNodeWithText("No queue yet. Play something from the Library or a playlist.").assertIsDisplayed()
    }
}
