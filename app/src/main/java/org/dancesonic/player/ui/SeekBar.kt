package org.dancesonic.player.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.dancesonic.player.playback.PlayerController

/**
 * The position in the track now playing, with a slider to move it. The thumb follows playback; while it is dragged, it
 * stays where the finger is, and the seek happens when the finger lifts. Disabled while the length is unknown.
 */
@Composable
internal fun SeekBar(player: PlayerController) {
    val progress by player.progress.collectAsStateWithLifecycle()
    var dragging by remember { mutableStateOf<Float?>(null) }
    val length = progress.durationMs
    val shownMs = dragging?.toLong() ?: progress.positionMs
    Column(Modifier.fillMaxWidth()) {
        Slider(
            value = dragging ?: progress.positionMs.toFloat(),
            onValueChange = { dragging = it },
            onValueChangeFinished = {
                dragging?.let { player.seekTo(it.toLong()) }
                dragging = null
            },
            valueRange = 0f..(if (length > 0) length.toFloat() else 1f),
            enabled = length > 0,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatLength((shownMs / MILLIS_PER_SECOND).toInt()), style = MaterialTheme.typography.labelSmall)
            Text(
                if (length > 0) formatLength((length / MILLIS_PER_SECOND).toInt()) else "--:--",
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

private const val MILLIS_PER_SECOND = 1_000L
