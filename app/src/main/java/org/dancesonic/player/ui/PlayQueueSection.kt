package org.dancesonic.player.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.dancesonic.player.playback.PlayQueue

/** The songs of the current playback session, in order, with the ones already played before the current one. */
@Composable
internal fun PlayQueueSection(
    queue: PlayQueue,
    onPlayAt: (Int) -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Text("Queue", style = MaterialTheme.typography.titleMedium)
        if (queue.entries.isEmpty()) {
            Text(
                "No queue yet. Play something from the Library or a playlist.",
                style = MaterialTheme.typography.bodyMedium,
            )
            return
        }
        LazyColumn(Modifier.heightIn(max = QUEUE_HEIGHT)) {
            itemsIndexed(queue.entries) { index, entry ->
                val position =
                    when {
                        index == queue.currentIndex -> "Now playing"
                        index < queue.currentIndex -> "Played"
                        else -> "Up next"
                    }
                ListRow(
                    title = entry.title,
                    subtitle = entry.artist?.let { "$position · $it" } ?: position,
                    onClick = { onPlayAt(index) },
                )
            }
        }
        HorizontalDivider()
    }
}

private val QUEUE_HEIGHT = 320.dp
