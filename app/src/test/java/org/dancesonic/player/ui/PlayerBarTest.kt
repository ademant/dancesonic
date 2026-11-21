package org.dancesonic.player.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.dancesonic.player.AppContainer
import org.dancesonic.player.playback.PlaybackUiState
import org.dancesonic.player.testsupport.TestViewModelHost
import org.dancesonic.player.testsupport.newTestContainer
import org.dancesonic.player.testsupport.resetDatabase
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** While nothing is queued the player bar is one line, and its controls are one tap away (#105). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PlayerBarTest {
    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var container: AppContainer
    private val host = TestViewModelHost()

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        container = newTestContainer()
        runBlocking { container.resetDatabase() }
    }

    @After
    fun tearDown() {
        host.clear()
        container.database.close()
        Dispatchers.resetMain()
    }

    private fun show() {
        val playback = host.create { PlaybackSettingsViewModel(container) }
        val library = host.create { LibraryViewModel(container) }
        composeRule.setContent {
            MaterialTheme { PlayerBar(container.player, playback, library, onExpand = {}) }
        }
    }

    @Test
    fun `with nothing queued the bar is one line without transport or tempo controls`() {
        show()

        composeRule.onNodeWithText("$NOTHING_PLAYING. Choose a track to start.").assertExists()
        composeRule.onAllNodesWithText("Prev").assertCountEquals(0)
        composeRule.onAllNodesWithText("Keep").assertCountEquals(0)
    }

    @Test
    fun `the idle controls open with the transport disabled, and close again`() {
        show()

        composeRule.onNodeWithText(SHOW_CONTROLS).performClick()
        composeRule.onNodeWithText("Prev").assertIsNotEnabled()
        composeRule.onNodeWithText("Play").assertIsNotEnabled()
        composeRule.onNodeWithText("Next").assertIsNotEnabled()
        composeRule.onNodeWithText("Keep").assertExists()

        composeRule.onNodeWithText(HIDE_CONTROLS).performClick()
        composeRule.onAllNodesWithText("Prev").assertCountEquals(0)
    }

    @Test
    fun `a player state with a queue is not idle`() {
        assertTrue(PlaybackUiState().idle)
        assertFalse(PlaybackUiState(queueLength = 3, hasNext = true).idle)
    }
}
