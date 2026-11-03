package org.dancesonic.player.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
import org.dancesonic.player.testsupport.seedOneAlbum
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
class LibraryScreensTest {
    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var container: AppContainer
    private val host = TestViewModelHost()

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        container = newTestContainer()
        runBlocking {
            container.resetDatabase()
            container.seedOneAlbum()
        }
    }

    @After
    fun tearDown() {
        host.clear()
        container.database.close()
        Dispatchers.resetMain()
    }

    @Test
    fun `library drills from artist to album to track`() {
        val library = host.create { LibraryViewModel(container) }
        val playlists = host.create { PlaylistViewModel(container) }
        val connect = host.create { ConnectViewModel(container) }
        composeRule.setContent {
            MaterialTheme { LibraryScreen(library, container.player, playlists, connect) }
        }

        composeRule.waitUntilAtLeastOneExists(hasText("Ada"))
        composeRule.onNodeWithText("Ada").performClick()
        composeRule.waitUntilAtLeastOneExists(hasText("Night"))
        composeRule.onNodeWithText("Night").performClick()
        composeRule.waitUntilAtLeastOneExists(hasText("One"))
        composeRule.onNodeWithText("One").assertIsDisplayed()
    }

    @Test
    fun `dance screen lists dance types and their tracks`() {
        runBlocking {
            container.database.danceMetadataDao().replaceFor("rec-1", listOf("Waltz"), 1L)
        }
        val library = host.create { LibraryViewModel(container) }
        val playlists = host.create { PlaylistViewModel(container) }
        composeRule.setContent {
            MaterialTheme { DanceScreen(library, container.player, playlists) }
        }

        composeRule.waitUntilAtLeastOneExists(hasText("Waltz"))
        composeRule.onNodeWithText("Waltz").performClick()
        composeRule.waitUntilAtLeastOneExists(hasText("One"))
        composeRule.onNodeWithText("One").assertIsDisplayed()
    }
}
