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
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.dancesonic.player.data.ShoesView
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

/**
 * The cover, title, release, artist and dance of a track, with a link to its dance description when DanceBrainz has
 * one. The link opens the recording's DanceBrainz page.
 */
@Composable
internal fun TrackHeader(
    detail: TrackDetail,
    libraryVm: LibraryViewModel,
    coverSize: Dp,
) {
    val track = detail.track
    val descriptionUrl by produceState<String?>(null, track.mbid) {
        value =
            track.mbid?.let { mbid ->
                if (libraryVm.hasDanceDescription(mbid)) libraryVm.danceBrainzPageUrl(mbid) else null
            }
    }
    val uriHandler = LocalUriHandler.current
    Column(Modifier.fillMaxWidth()) {
        CoverImage(
            url = libraryVm.coverUrl(track.albumId, COVER_PX_LARGE),
            contentDescription = detail.albumName,
            size = coverSize,
        )
        Text(track.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
        detail.albumName?.let { Text("Release: $it", style = MaterialTheme.typography.bodyMedium) }
        Text("Artist: ${track.artistName}", style = MaterialTheme.typography.bodyMedium)
        Text(
            if (detail.danceTypes.isEmpty()) {
                "Dance: none assigned"
            } else {
                "Dance: ${detail.danceTypes.joinToString(", ")}"
            },
            style = MaterialTheme.typography.bodyMedium,
        )
        descriptionUrl?.let { url ->
            TextButton(onClick = { uriHandler.openUri(url) }) { Text("Dance description") }
        }
    }
}

/** The facts about one track that are not in its header: length, tempo and shoes rating. */
@Composable
internal fun TrackFacts(
    detail: TrackDetail,
    shoes: ShoesView?,
) {
    val track = detail.track
    Column(Modifier.fillMaxWidth()) {
        Text(track.durationSec?.let { "Length ${formatLength(it)}" } ?: "Length unknown")
        Text(detail.bpm?.let { "Tempo $it BPM" } ?: "Tempo unknown")
        Text(shoesFacts(shoes, hasMbid = track.mbid != null))
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
    val shoes by libraryVm.shoes.collectAsStateWithLifecycle()
    AlertDialog(
        onDismissRequest = onDismiss,
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                val current = detail
                if (current == null) {
                    Text("Loading…")
                } else {
                    TrackHeader(current, libraryVm, coverSize = PREVIEW_COVER)
                    TrackFacts(current, current.track.mbid?.let { shoes[it] })
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
    val shoes by libraryVm.shoes.collectAsStateWithLifecycle()
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).verticalScroll(rememberScrollState()),
        ) {
            val current = detail
            if (current == null) {
                Text("Nothing playing")
            } else {
                TrackHeader(current, libraryVm, coverSize = SHEET_COVER)
                TrackFacts(current, current.track.mbid?.let { shoes[it] })
                SeekBar(player)
                current.track.mbid?.let { ChapterPanel(it, player, libraryVm) }
            }
        }
    }
}

private const val SECONDS_PER_MINUTE = 60
private val PREVIEW_COVER = 120.dp
private val SHEET_COVER = 220.dp
private const val SECONDS_PER_HOUR = 3600
