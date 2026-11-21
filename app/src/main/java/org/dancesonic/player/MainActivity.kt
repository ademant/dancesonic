package org.dancesonic.player

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import org.dancesonic.player.ui.DanceSonicRoot
import org.dancesonic.player.ui.applySystemBars

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applySystemBars()
        val container = (application as DanceSonicApp).container
        setContent {
            MaterialTheme {
                DanceSonicRoot(container)
            }
        }
    }
}
