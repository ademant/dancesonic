package org.dancesonic.player

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import org.dancesonic.player.ui.DanceSonicRoot

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as DanceSonicApp).container
        setContent {
            MaterialTheme {
                DanceSonicRoot(container)
            }
        }
    }
}
