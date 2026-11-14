package org.dancesonic.player.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEditable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.waitUntilAtLeastOneExists
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.dancesonic.player.AppContainer
import org.dancesonic.player.testsupport.TestViewModelHost
import org.dancesonic.player.testsupport.newTestContainer
import org.dancesonic.player.testsupport.resetDatabase
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class, ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PlaylistScreensTest {
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

    @Test
    fun `typing a course name and tapping Create lists the new playlist`() {
        val playlists = host.create { PlaylistViewModel(container) }
        composeRule.setContent {
            MaterialTheme { PlaylistsScreen(playlists, onOpenConfig = {}) }
        }

        composeRule.onNode(isEditable()).performTextInput("Waltz course")
        composeRule.onNodeWithText("Create").performClick()

        composeRule.waitUntilAtLeastOneExists(hasText("Waltz course"))
        composeRule.onNodeWithText("Waltz course").assertIsDisplayed()
    }
}
