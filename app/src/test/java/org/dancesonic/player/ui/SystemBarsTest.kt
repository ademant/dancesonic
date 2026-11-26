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

/** System bar icons follow the app's theme (#107), which follows the device's dark mode (#132). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SystemBarsTest {
    private fun iconsAreDark(dark: Boolean): Pair<Boolean, Boolean> {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        activity.applySystemBars(dark)
        val bars = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
        return bars.isAppearanceLightStatusBars to bars.isAppearanceLightNavigationBars
    }

    @Test
    @Config(qualifiers = "night")
    fun `a light app theme gets dark status and navigation icons, even on a device in dark mode`() {
        val (status, navigation) = iconsAreDark(dark = false)
        assertTrue(status)
        assertTrue(navigation)
    }

    @Test
    @Config(qualifiers = "notnight")
    fun `on a device in light mode the light app gets dark icons`() {
        val (status, navigation) = iconsAreDark(dark = false)
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
