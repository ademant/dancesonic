package org.dancesonic.player.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import org.dancesonic.player.data.ShoesView
import java.util.Locale

/** The highest shoes rating DanceBrainz accepts. */
private const val MAX_SHOES = 5

/** What a track row shows about shoes: [line] is the text under the title, [canRate] enables the Shoes button. */
internal data class TrackShoes(
    val line: String?,
    val canRate: Boolean,
)

/** The shoes text and button state for one track. */
internal fun trackShoes(
    mbid: String?,
    view: ShoesView?,
    signedIn: Boolean,
): TrackShoes {
    val reason = shoesReason(mbid, signedIn)
    val summary = shoesSummary(view)
    return TrackShoes(
        line = listOfNotNull(summary, reason).joinToString(" · ").ifEmpty { null },
        canRate = reason == null,
    )
}

/** The one-line shoes state of a track, or null when there is nothing to show. */
internal fun shoesSummary(view: ShoesView?): String? {
    if (view == null) return null
    val parts =
        listOfNotNull(
            view.mine?.let { "Shoes $it" },
            view.median?.let { "median ${formatOneDecimal(it)} (${view.raters})" },
            "not sent yet".takeIf { view.pending },
        )
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
}

/**
 * Why a track cannot be rated now, or null when it can. Shown in place of the summary when the control is off,
 * so the disabled button is never silent.
 */
internal fun shoesReason(
    mbid: String?,
    signedIn: Boolean,
): String? =
    when {
        mbid == null -> "Cannot be rated: no MusicBrainz ID"
        !signedIn -> "Sign in to DanceBrainz to rate"
        else -> null
    }

/** Rating dialog for one track. Changes apply at once; the dialog stays open to show whether they were sent. */
@Composable
internal fun ShoesDialog(
    title: String,
    view: ShoesView?,
    onRate: (Int?) -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Shoes: $title") },
        text = {
            Column {
                Text(yourRatingLine(view))
                Text(medianLine(view))
                if (view?.pending == true) {
                    Text(view.error?.let { "Not sent yet: $it" } ?: "Waiting to be sent.")
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    for (value in 1..MAX_SHOES) {
                        TextButton(onClick = { onRate(value) }) { Text("$value") }
                    }
                }
                TextButton(onClick = { onRate(null) }) { Text("Clear rating") }
                if (view?.pending == true) {
                    TextButton(onClick = onRetry) { Text("Send again") }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

private fun yourRatingLine(view: ShoesView?): String =
    view?.mine?.let { "Your rating: $it of $MAX_SHOES" } ?: "You have not rated this track."

private fun medianLine(view: ShoesView?): String {
    val median = view?.median
    return if (view != null && median != null) {
        "Median ${formatOneDecimal(median)} from ${view.raters} raters."
    } else {
        "No public median yet."
    }
}

private fun formatOneDecimal(value: Double): String = String.format(Locale.ROOT, "%.1f", value)
