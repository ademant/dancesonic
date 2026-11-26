package org.dancesonic.player.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.dancesonic.player.playback.PlayQueue

/** What TalkBack says a tap on the song playing now does: it starts the song again. */
internal const val RESTART_LABEL = "Play from the start"

/** What TalkBack says a tap on any other song does. */
internal const val PLAY_LABEL = "Play this song"

/**
 * The songs of the current playback session as rows of a lazy list, in order, with the ones already played before the
 * current one. Each row is keyed by the song and its occurrence, so a song queued twice keeps two rows (#129). The
 * player sheet adds these rows to its own list, so a long queue scrolls in one container.
 */
internal fun LazyListScope.playQueueItems(
    queue: PlayQueue,
    onPlayAt: (Int) -> Unit,
) {
    item(key = "queue-title") { Text("Queue", style = MaterialTheme.typography.titleMedium) }
    if (queue.entries.isEmpty()) {
        item(key = "queue-empty") {
            Text(
                "No queue yet. Play something from the Library or a playlist.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        return
    }
    itemsIndexed(queue.entries, key = { _, entry -> entry.key }) { index, entry ->
        val current = index == queue.currentIndex
        val position =
            when {
                current -> "Now playing"
                index < queue.currentIndex -> "Played"
                else -> "Up next"
            }
        ListRow(
            title = entry.title,
            subtitle = entry.artist?.let { "$position · $it" } ?: position,
            onClickLabel = if (current) RESTART_LABEL else PLAY_LABEL,
            onClick = { onPlayAt(index) },
        )
    }
    item(key = "queue-end") { HorizontalDivider() }
}

/** The queue on its own, in a list of bounded height. The player sheet uses [playQueueItems] instead. */
@Composable
internal fun PlayQueueSection(
    queue: PlayQueue,
    onPlayAt: (Int) -> Unit,
) {
    LazyColumn(Modifier.fillMaxWidth().heightIn(max = QUEUE_HEIGHT)) { playQueueItems(queue, onPlayAt) }
}

private val QUEUE_HEIGHT = 320.dp
