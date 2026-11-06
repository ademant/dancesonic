package org.dancesonic.player.ui

import org.dancesonic.player.data.ShoesView
import java.util.Locale

/** The highest shoes rating DanceBrainz accepts. */
internal const val MAX_SHOES = 5

/** What a track row shows about shoes and edits: [line] is the text under the title, [canRate] enables Edit. */
internal data class TrackShoes(
    val line: String?,
    val canRate: Boolean,
)

/** The shoes text and Edit button state for one track. */
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
 * Why a track cannot be edited now, or null when it can. Shown in place of the summary when the Edit button is off,
 * so the disabled button is never silent.
 */
internal fun shoesReason(
    mbid: String?,
    signedIn: Boolean,
): String? =
    when {
        mbid == null -> "Cannot be edited: no MusicBrainz ID"
        !signedIn -> "Sign in to DanceBrainz to edit"
        else -> null
    }

internal fun yourRatingLine(view: ShoesView?): String =
    view?.mine?.let { "Your rating: $it of $MAX_SHOES" } ?: "You have not rated this track."

internal fun medianLine(view: ShoesView?): String {
    val median = view?.median
    return if (view != null && median != null) {
        "Median ${formatOneDecimal(median)} from ${view.raters} raters."
    } else {
        "No public median yet."
    }
}

internal fun formatOneDecimal(value: Double): String = String.format(Locale.ROOT, "%.1f", value)
