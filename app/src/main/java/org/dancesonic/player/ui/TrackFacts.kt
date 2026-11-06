package org.dancesonic.player.ui

import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.dancesonic.player.data.ShoesView
import org.dancesonic.player.data.TrackDetail

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
 * The shoes rating shown for a track. The viewer's own rating comes first; the public median is the fallback. The
 * own rating is only present while signed in, so the same rule holds with and without sign-in.
 */
internal fun shoesFacts(
    view: ShoesView?,
    hasMbid: Boolean,
): String {
    if (!hasMbid) return "No shoes rating: no MusicBrainz ID"
    val mine = view?.mine
    val median = view?.median
    val raters = view?.raters ?: 0
    val text =
        when {
            mine != null -> "Shoes rating: $mine (yours)"
            median != null -> "Shoes rating: median ${formatOneDecimal(median)} from $raters raters"
            else -> "No shoes rating yet"
        }
    return if (view?.pending == true) "$text · not sent yet" else text
}

/** The facts about one track: length, dance types, tempo and shoes rating, as the rows and the Dance view show them. */
@Composable
internal fun TrackFacts(
    detail: TrackDetail,
    shoes: ShoesView?,
) {
    val track = detail.track
    Column(Modifier.fillMaxWidth()) {
        Text(track.title, style = MaterialTheme.typography.titleMedium)
        Text(track.artistName, style = MaterialTheme.typography.bodyMedium)
        detail.albumName?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        Text(track.durationSec?.let { "Length ${formatLength(it)}" } ?: "Length unknown")
        Text(
            if (detail.danceTypes.isEmpty()) {
                "No dance assigned"
            } else {
                "Dance: ${detail.danceTypes.joinToString(", ")}"
            },
        )
        Text(detail.bpm?.let { "Tempo $it BPM" } ?: "Tempo unknown")
        Text(shoesFacts(shoes, hasMbid = track.mbid != null))
    }
}

/** Preview of a track before it plays. Play starts it in the list, as tapping a row used to. */
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
    val shoes by libraryVm.shoes.collectAsStateWithLifecycle()
    AlertDialog(
        onDismissRequest = onDismiss,
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                val current = detail
                if (current == null) Text("Loading…") else TrackFacts(current, current.track.mbid?.let { shoes[it] })
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
    onDismiss: () -> Unit,
) {
    val detail by libraryVm.nowPlaying.collectAsStateWithLifecycle()
    val shoes by libraryVm.shoes.collectAsStateWithLifecycle()
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).verticalScroll(rememberScrollState()),
        ) {
            val current = detail
            if (current == null) Text("Nothing playing") else TrackFacts(current, current.track.mbid?.let { shoes[it] })
        }
    }
}

private const val SECONDS_PER_MINUTE = 60
private const val SECONDS_PER_HOUR = 3600
