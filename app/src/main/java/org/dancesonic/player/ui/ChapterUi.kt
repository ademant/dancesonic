package org.dancesonic.player.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.dancesonic.player.dance.ChapterChange
import org.dancesonic.player.dance.DanceSuggestion
import org.dancesonic.player.data.db.ChapterEntity
import org.dancesonic.player.playback.PlayerController

/**
 * The chapter marks of the track now playing: a strip showing where each part is, the list, previous and next, and
 * (when signed in) marking and editing. Chapter times are shown as approximate, and not changed, when the file's
 * length differs from the recording's.
 */
@Composable
internal fun ChapterPanel(
    mbid: String,
    player: PlayerController,
    libraryVm: LibraryViewModel,
) {
    val chapters by remember(mbid) { libraryVm.chapters(mbid) }.collectAsStateWithLifecycle(emptyList())
    val progress by player.progress.collectAsStateWithLifecycle()
    val signedIn by libraryVm.signedIn.collectAsStateWithLifecycle()
    val message by libraryVm.chapterMessage.collectAsStateWithLifecycle()
    var editing by remember(mbid) { mutableStateOf<Int?>(null) }

    val position = progress.positionMs
    val current = currentChapterIndex(chapters, position)
    val approximate = chaptersApproximate(chapters, progress.durationMs)
    val canEdit = signedIn && !approximate

    Column(Modifier.fillMaxWidth().padding(top = 12.dp)) {
        Text("Chapters", style = MaterialTheme.typography.titleSmall)
        if (approximate) {
            Text(
                "The chapter times may be off: this file is ${formatLength(progress.durationMs.toSeconds())} long, " +
                    "DanceBrainz has ${formatLength((knownLengthMs(chapters) ?: 0L).toSeconds())}. " +
                    "Chapters of this file cannot be changed.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (chapters.isEmpty()) {
            Text("No chapter marks", style = MaterialTheme.typography.bodySmall)
        } else {
            ChapterStrip(chapters, progress.durationMs, current) { player.seekTo(it) }
            chapters.forEachIndexed { index, chapter ->
                ChapterRow(
                    name = chapterName(chapter, index + 1),
                    startMs = chapter.startMs,
                    playing = index == current,
                    onPlay = { player.seekTo(chapter.startMs) },
                    onEdit = if (canEdit) ({ editing = index }) else null,
                )
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            val previous = previousChapterStart(chapters, position)
            val next = nextChapterStart(chapters, position)
            TextButton(onClick = { previous?.let(player::seekTo) }, enabled = previous != null) {
                Text("Previous chapter")
            }
            TextButton(onClick = { next?.let(player::seekTo) }, enabled = next != null) {
                Text("Next chapter")
            }
        }
        if (canEdit) {
            TextButton(onClick = { libraryVm.markChapter(mbid, position) }) { Text("Mark chapter here") }
        }
        message?.let { text ->
            Text(text, style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = libraryVm::clearChapterMessage) { Text("OK") }
        }
    }

    editing?.let { index ->
        val chapter = chapters.getOrNull(index)
        if (chapter != null) {
            ChapterEditDialog(
                chapter = chapter,
                heading = "Chapter ${index + 1}",
                positionMs = position,
                libraryVm = libraryVm,
                onDismiss = { editing = null },
            )
        }
    }
}

/** Each chapter as a segment of one bar, sized by its length. The playing one is highlighted; a tap seeks to it. */
@Composable
private fun ChapterStrip(
    chapters: List<ChapterEntity>,
    lengthMs: Long,
    current: Int,
    onSeek: (Long) -> Unit,
) {
    if (lengthMs <= 0L) return
    Row(Modifier.fillMaxWidth().height(STRIP_HEIGHT).clip(RoundedCornerShape(STRIP_CORNER))) {
        chapters.forEachIndexed { index, chapter ->
            val end = chapter.endMs ?: lengthMs
            val weight = (end - chapter.startMs).coerceAtLeast(1L).toFloat()
            Box(
                Modifier
                    .weight(weight)
                    .fillMaxHeight()
                    .background(
                        if (index == current) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        },
                    )
                    .clickable { onSeek(chapter.startMs) },
            )
        }
    }
}

@Composable
private fun ChapterRow(
    name: String,
    startMs: Long,
    playing: Boolean,
    onPlay: () -> Unit,
    onEdit: (() -> Unit)?,
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onPlay).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            (if (playing) "▶ " else "") + name,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        Text(formatLength(startMs.toSeconds()), style = MaterialTheme.typography.labelSmall)
        onEdit?.let { TextButton(onClick = it) { Text("Edit") } }
    }
}

/**
 * Edits one chapter: its title, its dance, its start (moved to the position now playing), or removes it. Each action
 * is sent at once; the dialog closes after it, and the result is shown in the panel.
 */
@Composable
private fun ChapterEditDialog(
    chapter: ChapterEntity,
    heading: String,
    positionMs: Long,
    libraryVm: LibraryViewModel,
    onDismiss: () -> Unit,
) {
    var title by rememberSaveable(chapter.startMs) { mutableStateOf(chapter.title.orEmpty()) }
    var query by rememberSaveable(chapter.startMs) { mutableStateOf("") }
    var chosen by remember(chapter.startMs) { mutableStateOf<DanceSuggestion?>(null) }
    var clearDance by remember(chapter.startMs) { mutableStateOf(false) }
    val suggestions by libraryVm.suggestions.collectAsStateWithLifecycle()
    val suggestError by libraryVm.suggestError.collectAsStateWithLifecycle()

    LaunchedEffect(query) { libraryVm.suggestDances(query) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(heading) },
        text = {
            Column(Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it.take(MAX_TITLE) },
                    label = { Text("Title") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "Dance: " +
                        when {
                            clearDance -> "none"
                            chosen != null -> chosen?.name.orEmpty()
                            else -> chapter.danceName ?: "none"
                        },
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 8.dp),
                )
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Find a dance") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                suggestError?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                suggestions.take(MAX_SUGGESTIONS).forEach { suggestion ->
                    Text(
                        suggestion.path.replace("/", " › "),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier =
                            Modifier.fillMaxWidth().clickable {
                                chosen = suggestion
                                clearDance = false
                            }.padding(vertical = 6.dp),
                    )
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = {
                        clearDance = true
                        chosen = null
                    }) { Text("No dance") }
                    TextButton(onClick = {
                        libraryVm.changeChapter(chapter.mbid, chapter.startMs, ChapterChange(newStartMs = positionMs))
                        onDismiss()
                    }) { Text("Move here") }
                }
                TextButton(onClick = {
                    libraryVm.deleteChapter(chapter.mbid, chapter.startMs)
                    onDismiss()
                }) { Text("Delete chapter") }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                libraryVm.changeChapter(chapter.mbid, chapter.startMs, changeFor(chapter, title, chosen, clearDance))
                onDismiss()
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * What the Save button sends: the title when it changed (blank clears it), and the dance when one was picked or
 * cleared. Fields that did not change are left out, so the server keeps them.
 */
internal fun changeFor(
    chapter: ChapterEntity,
    title: String,
    chosen: DanceSuggestion?,
    clearDance: Boolean,
): ChapterChange {
    val trimmed = title.trim()
    val titleChanged = trimmed != chapter.title.orEmpty()
    val tagId = chosen?.tagId
    return ChapterChange(
        title = trimmed.takeIf { titleChanged && it.isNotEmpty() },
        clearTitle = titleChanged && trimmed.isEmpty(),
        danceTagId = tagId?.takeIf { !clearDance },
        clearDance = clearDance,
    )
}

private fun Long.toSeconds(): Int = (this / MILLIS_PER_SECOND).toInt()

private const val MILLIS_PER_SECOND = 1_000L
private const val MAX_TITLE = 200
private const val MAX_SUGGESTIONS = 6
private val STRIP_HEIGHT = 10.dp
private val STRIP_CORNER = 4.dp
