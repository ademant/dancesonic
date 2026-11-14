package org.dancesonic.player.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.dancesonic.player.data.TrackDetail
import org.dancesonic.player.playback.PlayerController

/** A track length as `m:ss`, or `h:mm:ss` from an hour. */
internal fun formatLength(seconds: Int): String {
    val hours = seconds / SECONDS_PER_HOUR
    val minutes = (seconds % SECONDS_PER_HOUR) / SECONDS_PER_MINUTE
    val rest = seconds % SECONDS_PER_MINUTE
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, rest)
    } else {
        "%d:%02d".format(minutes, rest)
    }
}

/**
 * The fixed facts of a track, at the top of its detail view: the cover, title, release and artist. Every line after
 * the title has the same font size, as the editable rows below it.
 */
@Composable
internal fun TrackHeader(
    detail: TrackDetail,
    libraryVm: LibraryViewModel,
    coverSize: Dp,
) {
    val track = detail.track
    Column(Modifier.fillMaxWidth()) {
        CoverImage(
            url = libraryVm.coverUrl(track.albumId, COVER_PX_LARGE),
            contentDescription = detail.albumName,
            size = coverSize,
        )
        Text(track.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
        detail.albumName?.let { Text("Release: $it", style = MaterialTheme.typography.bodyLarge) }
        Text("Artist: ${track.artistName}", style = MaterialTheme.typography.bodyLarge)
    }
}

/** The length and tempo of a track, the fixed facts under its header. */
@Composable
internal fun TrackFacts(detail: TrackDetail) {
    val track = detail.track
    Column(Modifier.fillMaxWidth()) {
        Text(
            track.durationSec?.let { "Length ${formatLength(it)}" } ?: "Length unknown",
            style = MaterialTheme.typography.bodyLarge,
        )
        Text(detail.bpm?.let { "Tempo $it BPM" } ?: "Tempo unknown", style = MaterialTheme.typography.bodyLarge)
    }
}

/** The editable entries of a track, or a note why it cannot be edited (no MusicBrainz ID). */
@Composable
internal fun TrackEntries(
    detail: TrackDetail,
    libraryVm: LibraryViewModel,
) {
    val mbid = detail.track.mbid
    if (mbid == null) {
        Text("Cannot be edited: no MusicBrainz ID", style = MaterialTheme.typography.bodyLarge)
    } else {
        TrackDetailEditor(mbid, libraryVm)
    }
}

/** The facts about a track, opened from its Info button. Play starts it, as tapping the row does. */
@Composable
internal fun TrackPreviewDialog(
    trackId: String,
    libraryVm: LibraryViewModel,
    onPlay: () -> Unit,
    onDismiss: () -> Unit,
) {
    val detail by produceState<TrackDetail?>(initialValue = null, trackId) {
        value = libraryVm.trackDetail(trackId)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                val current = detail
                if (current == null) {
                    Text("Loading…")
                } else {
                    TrackHeader(current, libraryVm, coverSize = PREVIEW_COVER)
                    TrackFacts(current)
                    TrackEntries(current, libraryVm)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onPlay()
                onDismiss()
            }) {
                Text("Play")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

/**
 * The expanded player: the track now playing, opened from the player bar. Swipe down closes it; playback goes on.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NowPlayingSheet(
    libraryVm: LibraryViewModel,
    player: PlayerController,
    onDismiss: () -> Unit,
) {
    val detail by libraryVm.nowPlaying.collectAsStateWithLifecycle()
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        modifier = Modifier.fillMaxHeight(),
    ) {
        Column(
            Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp).verticalScroll(rememberScrollState()),
        ) {
            val current = detail
            if (current == null) {
                Text("Nothing playing")
            } else {
                TrackHeader(current, libraryVm, coverSize = SHEET_COVER)
                TrackFacts(current)
                TrackEntries(current, libraryVm)
                SeekBar(player, libraryVm.nowPlayingWaveform)
                TransportRow(player)
                current.track.mbid?.let { ChapterPanel(it, player, libraryVm) }
            }
        }
    }
}

private const val SECONDS_PER_MINUTE = 60
private val PREVIEW_COVER = 120.dp
private val SHEET_COVER = 220.dp
private const val SECONDS_PER_HOUR = 3600
