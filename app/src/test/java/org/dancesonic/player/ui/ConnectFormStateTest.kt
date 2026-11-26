package org.dancesonic.player.ui

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
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
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The connect form as found on a phone: an error stayed on screen after switching to another way of connecting, and
 * a typed API key was wiped even when the attempt was refused before anything was sent.
 */
@OptIn(ExperimentalCoroutinesApi::class, ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ConnectFormStateTest {
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

    private fun ComposeUiTest.showApiKeyForm() {
        val vm = host.create { ConnectViewModel(container) }
        setContent { ConnectScreen(vm) }
        // The first node with this text is the method chip; the field's label comes later in the layout.
        onAllNodes(hasText("API key"))[0].performClick()
    }

    private fun ComposeUiTest.address() = onAllNodes(hasSetTextAction())[0]

    private fun ComposeUiTest.keyField() = onAllNodes(hasSetTextAction())[1]

    private fun ComposeUiTest.typedKey(): String =
        keyField().fetchSemanticsNode().config[SemanticsProperties.EditableText].text

    @Test
    fun `an error from one way of connecting is gone after switching to another`() =
        runComposeUiTest {
            showApiKeyForm()
            onNodeWithText("Connect").performClick()
            onNodeWithText("Enter an API key.").assertExists()

            onNodeWithText("QR code").performClick()

            onNodeWithText("Enter an API key.").assertDoesNotExist()
        }

    @Test
    fun `tapping the way already shown keeps its error`() =
        runComposeUiTest {
            showApiKeyForm()
            onNodeWithText("Connect").performClick()

            onAllNodes(hasText("API key"))[0].performClick()

            onNodeWithText("Enter an API key.").assertExists()
        }

    @Test
    fun `a key refused before anything is sent stays in its field`() =
        runComposeUiTest {
            showApiKeyForm()
            address().performTextClearance()
            address().performTextInput("http://music.example.com")
            keyField().performTextInput("typed-key")

            onNodeWithText("Connect").performClick()
            waitForIdle()

            onNodeWithText("The server address must start with https://", substring = true).assertExists()
            assertEquals("•••••••••", typedKey())
        }

    @Test
    fun `a key that was sent is cleared from its field`() =
        runComposeUiTest {
            showApiKeyForm()
            address().performTextClearance()
            // Nothing listens there: the attempt starts, then fails to connect.
            address().performTextInput("https://127.0.0.1:1")
            keyField().performTextInput("typed-key")

            onNodeWithText("Connect").performClick()
            waitForIdle()

            assertEquals("", typedKey())
        }
}
