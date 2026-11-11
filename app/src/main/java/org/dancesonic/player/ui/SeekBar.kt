package org.dancesonic.player.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.StateFlow
import org.dancesonic.player.analysis.PEAK_MAX
import org.dancesonic.player.playback.PlayerController

/**
 * The position in the track now playing, with a slider to move it. The thumb follows playback; while it is dragged, it
 * stays where the finger is, and the seek happens when the finger lifts. Disabled while the length is unknown.
 *
 * When [waveform] has peaks, they are drawn behind the slider, and the part already played is coloured.
 */
@Composable
internal fun SeekBar(
    player: PlayerController,
    waveform: StateFlow<List<Int>?>,
) {
    val progress by player.progress.collectAsStateWithLifecycle()
    val peaks by waveform.collectAsStateWithLifecycle()
    var dragging by remember { mutableStateOf<Float?>(null) }
    val length = progress.durationMs
    val shownMs = dragging?.toLong() ?: progress.positionMs
    val colors =
        if (peaks == null) {
            SliderDefaults.colors()
        } else {
            // The waveform replaces the slider's own track, so the thumb is all that the slider draws.
            SliderDefaults.colors(activeTrackColor = Color.Transparent, inactiveTrackColor = Color.Transparent)
        }
    Column(Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().height(SEEK_HEIGHT), contentAlignment = Alignment.Center) {
            peaks?.let { values ->
                val fraction = if (length > 0) shownMs.toFloat() / length.toFloat() else 0f
                WaveformBars(values, fraction, Modifier.matchParentSize())
            }
            Slider(
                value = dragging ?: progress.positionMs.toFloat(),
                onValueChange = { dragging = it },
                onValueChangeFinished = {
                    dragging?.let { player.seekTo(it.toLong()) }
                    dragging = null
                },
                valueRange = 0f..(if (length > 0) length.toFloat() else 1f),
                enabled = length > 0,
                colors = colors,
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatLength((shownMs / MILLIS_PER_SECOND).toInt()), style = MaterialTheme.typography.labelSmall)
            Text(
                if (length > 0) formatLength((length / MILLIS_PER_SECOND).toInt()) else "--:--",
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

/** One bar per peak, centred on the middle line. Bars before [fraction] of the track are drawn as played. */
@Composable
private fun WaveformBars(
    peaks: List<Int>,
    fraction: Float,
    modifier: Modifier,
) {
    val playedColor = MaterialTheme.colorScheme.primary
    val restColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = REST_ALPHA)
    Canvas(modifier) {
        if (peaks.isEmpty()) return@Canvas
        val slot = size.width / peaks.size
        val barWidth = slot * BAR_SHARE
        val middle = size.height / 2f
        peaks.forEachIndexed { index, peak ->
            val height = maxOf(MIN_BAR_PX, peak.toFloat() / PEAK_MAX * size.height)
            val played = (index + HALF_SLOT) / peaks.size <= fraction
            drawRect(
                color = if (played) playedColor else restColor,
                topLeft = Offset(index * slot + (slot - barWidth) / 2f, middle - height / 2f),
                size = Size(barWidth, height),
            )
        }
    }
}

private const val MILLIS_PER_SECOND = 1_000L
private const val BAR_SHARE = 0.6f
private const val REST_ALPHA = 0.38f
private const val MIN_BAR_PX = 2f
private const val HALF_SLOT = 0.5f
private val SEEK_HEIGHT = 40.dp
