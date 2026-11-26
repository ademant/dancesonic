package org.dancesonic.player

import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import org.dancesonic.player.ui.DanceSonicRoot
import org.dancesonic.player.ui.DanceSonicTheme
import org.dancesonic.player.ui.applySystemBars

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applySystemBars(dark = isNightMode())
        val container = (application as DanceSonicApp).container
        setContent {
            val dark = isSystemInDarkTheme()
            // The system bar icons follow the theme when the device switches between light and dark (#132).
            LaunchedEffect(dark) { applySystemBars(dark) }
            DanceSonicTheme(dark = dark) {
                DanceSonicRoot(container)
            }
        }
    }

    private fun isNightMode(): Boolean =
        resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
}
