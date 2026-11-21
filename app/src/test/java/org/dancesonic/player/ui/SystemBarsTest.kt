package org.dancesonic.player.ui

import androidx.activity.ComponentActivity
import androidx.core.view.WindowCompat
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** System bar icons follow the app's theme, whatever the device theme is (#107). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SystemBarsTest {
    private fun iconsAreDark(
        dark: Boolean = APP_THEME_DARK,
    ): Pair<Boolean, Boolean> {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        activity.applySystemBars(dark)
        val bars = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
        return bars.isAppearanceLightStatusBars to bars.isAppearanceLightNavigationBars
    }

    @Test
    @Config(qualifiers = "night")
    fun `on a device in dark mode the light app gets dark status and navigation icons`() {
        val (status, navigation) = iconsAreDark()
        assertTrue(status)
        assertTrue(navigation)
    }

    @Test
    @Config(qualifiers = "notnight")
    fun `on a device in light mode the light app gets dark icons too`() {
        val (status, navigation) = iconsAreDark()
        assertTrue(status)
        assertTrue(navigation)
    }

    @Test
    @Config(qualifiers = "notnight")
    fun `a dark app theme gets light icons, even on a device in light mode`() {
        val (status, navigation) = iconsAreDark(dark = true)
        assertFalse(status)
        assertFalse(navigation)
    }
}
