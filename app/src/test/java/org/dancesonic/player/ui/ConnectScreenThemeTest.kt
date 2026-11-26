package org.dancesonic.player.ui

import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithText
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The connect screen is readable in both themes. It is shown before the Scaffold, and once drew black text on the dark
 * background (#132). Checked on the drawn pixels of the heading: its text must stand out from its background.
 */
@OptIn(ExperimentalCoroutinesApi::class, ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class ConnectScreenThemeTest {
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

    /** The heading's background (its median luminance) and its text (the luminance furthest from it). */
    private fun headingContrast(dark: Boolean): Pair<Float, Float> {
        var range = 0f to 0f
        runComposeUiTest {
            val vm = host.create { ConnectViewModel(container) }
            setContent { DanceSonicTheme(dark = dark, dynamicColor = false) { ConnectScreen(vm) } }

            val pixels = onNodeWithText("Connect to your server").captureToImage().toPixelMap()
            val values =
                (0 until pixels.width).flatMap { x -> (0 until pixels.height).map { y -> pixels[x, y].luminance() } }
            val background = values.sorted()[values.size / 2]
            val text = if (dark) values.max() else values.min()
            range = background to text
        }
        return range
    }

    @Test
    @Config(qualifiers = "night")
    fun `in the dark theme the heading is light text on a dark background`() {
        val (background, text) = headingContrast(dark = true)

        assertTrue("background should be dark, was $background", background < DARK)
        assertTrue("text should be light, was $text", text > LIGHT)
    }

    @Test
    @Config(qualifiers = "notnight")
    fun `in the light theme the heading is dark text on a light background`() {
        val (background, text) = headingContrast(dark = false)

        assertTrue("background should be light, was $background", background > LIGHT)
        assertTrue("text should be dark, was $text", text < DARK)
    }

    private companion object {
        const val DARK = 0.2f
        const val LIGHT = 0.6f
    }
}
