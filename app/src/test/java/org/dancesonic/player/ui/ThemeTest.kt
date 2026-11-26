package org.dancesonic.player.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The app follows the system's dark mode (#132), with and without dynamic color. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ThemeTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun background(dynamicColor: Boolean): Pair<Color, Color> {
        var background = Color.Unspecified
        var onBackground = Color.Unspecified
        composeRule.setContent {
            DanceSonicTheme(dynamicColor = dynamicColor) {
                background = MaterialTheme.colorScheme.background
                onBackground = MaterialTheme.colorScheme.onBackground
            }
        }
        composeRule.waitForIdle()
        return background to onBackground
    }

    @Test
    @Config(qualifiers = "night")
    fun `a device in dark mode gets a dark theme`() {
        val (background, text) = background(dynamicColor = false)
        assertTrue(background.luminance() < DARK)
        assertTrue(text.luminance() > LIGHT)
    }

    @Test
    @Config(qualifiers = "notnight")
    fun `a device in light mode gets a light theme`() {
        val (background, text) = background(dynamicColor = false)
        assertTrue(background.luminance() > LIGHT)
        assertTrue(text.luminance() < DARK)
    }

    @Test
    @Config(qualifiers = "night")
    fun `dynamic color is dark too in dark mode`() {
        val (background, text) = background(dynamicColor = true)
        assertTrue(background.luminance() < DARK)
        assertTrue(text.luminance() > LIGHT)
    }

    private companion object {
        const val DARK = 0.2f
        const val LIGHT = 0.6f
    }
}
