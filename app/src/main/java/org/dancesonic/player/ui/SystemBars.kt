package org.dancesonic.player.ui

import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge

/** The translucent scrims behind three-button navigation, the same as `enableEdgeToEdge`'s defaults. */
private const val LIGHT_SCRIM = 0xE6FFFFFF.toInt()
private const val DARK_SCRIM = 0x801B1B1B.toInt()

/**
 * Draws edge to edge with system bar icons that match the app's theme (#107): light icons over a [dark] theme, dark
 * icons over a light one. The app follows the device's dark mode (#132), and the caller passes the theme it draws, so
 * the icons never disagree with the surface under them. The status bar stays transparent; three-button navigation
 * keeps a scrim, gesture navigation none.
 */
internal fun ComponentActivity.applySystemBars(dark: Boolean) {
    val status =
        if (dark) {
            SystemBarStyle.dark(Color.TRANSPARENT)
        } else {
            SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
        }
    val navigation = if (dark) SystemBarStyle.dark(DARK_SCRIM) else SystemBarStyle.light(LIGHT_SCRIM, DARK_SCRIM)
    enableEdgeToEdge(statusBarStyle = status, navigationBarStyle = navigation)
}
