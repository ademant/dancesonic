// The color literals are the palette itself; naming each one again would add nothing.
@file:Suppress("MagicNumber")

package org.dancesonic.player.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/** The greens of the launcher artwork, for devices without dynamic color. */
private val LightColors: ColorScheme =
    lightColorScheme(
        primary = Color(0xFF2E6B41),
        onPrimary = Color.White,
        primaryContainer = Color(0xFFB2F1BE),
        onPrimaryContainer = Color(0xFF00210C),
        secondary = Color(0xFF506352),
        onSecondary = Color.White,
        secondaryContainer = Color(0xFFD3E8D3),
        onSecondaryContainer = Color(0xFF0E1F12),
    )

private val DarkColors: ColorScheme =
    darkColorScheme(
        primary = Color(0xFF96D5A3),
        onPrimary = Color(0xFF003919),
        primaryContainer = Color(0xFF14522B),
        onPrimaryContainer = Color(0xFFB2F1BE),
        secondary = Color(0xFFB7CCB8),
        onSecondary = Color(0xFF223526),
        secondaryContainer = Color(0xFF394B3C),
        onSecondaryContainer = Color(0xFFD3E8D3),
    )

/**
 * The app's Material 3 theme. It follows the system's dark mode (#132), and on Android 12 and later the wallpaper's
 * colors unless [dynamicColor] is off.
 */
@Composable
fun DanceSonicTheme(
    dark: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colors =
        when {
            dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
                val context = LocalContext.current
                if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            }
            dark -> DarkColors
            else -> LightColors
        }
    MaterialTheme(colorScheme = colors, content = content)
}
