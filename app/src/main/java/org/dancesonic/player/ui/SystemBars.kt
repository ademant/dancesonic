package org.dancesonic.player.ui

import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge

/** Whether the app draws a dark theme. The UI uses the light Material theme on every device theme. */
internal const val APP_THEME_DARK = false

/** The translucent scrims behind three-button navigation, the same as `enableEdgeToEdge`'s defaults. */
private const val LIGHT_SCRIM = 0xE6FFFFFF.toInt()
private const val DARK_SCRIM = 0x801B1B1B.toInt()

/**
 * Draws edge to edge with system bar icons that match the app's theme, not the device's (#107). The default
 * `enableEdgeToEdge()` follows the device theme, so a phone in dark mode got light, barely visible icons over the
 * app's light surface. The status bar stays transparent; three-button navigation keeps a scrim, gesture navigation
 * none.
 */
internal fun ComponentActivity.applySystemBars(dark: Boolean = APP_THEME_DARK) {
    val status =
        if (dark) {
            SystemBarStyle.dark(Color.TRANSPARENT)
        } else {
            SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
        }
    val navigation = if (dark) SystemBarStyle.dark(DARK_SCRIM) else SystemBarStyle.light(LIGHT_SCRIM, DARK_SCRIM)
    enableEdgeToEdge(statusBarStyle = status, navigationBarStyle = navigation)
}
