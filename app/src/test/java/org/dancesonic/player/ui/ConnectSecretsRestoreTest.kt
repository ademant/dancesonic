package org.dancesonic.player.ui

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.StateRestorationTester
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performClick
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

/** A typed API key must not come back after the activity is recreated: it is not part of the saved state. */
@OptIn(ExperimentalCoroutinesApi::class, ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ConnectSecretsRestoreTest {
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
    fun `a typed API key is empty after the activity is recreated`() =
        runComposeUiTest {
            val vm = host.create { ConnectViewModel(container) }
            val restorer = StateRestorationTester(this)
            restorer.setContent { ConnectScreen(vm) }

            // The first node with this text is the method chip; the field's label comes later in the layout.
            onAllNodes(hasText("API key"))[0].performClick()
            onAllNodes(hasSetTextAction())[1].performTextInput("typed-secret")

            restorer.emulateSaveAndRestore()

            val restored = onAllNodes(hasSetTextAction())[1].fetchSemanticsNode()
            assertEquals("", restored.config[SemanticsProperties.EditableText].text)
        }
}
