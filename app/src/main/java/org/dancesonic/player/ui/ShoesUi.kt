package org.dancesonic.player.ui

import org.dancesonic.player.data.ShoesView
import java.util.Locale

/** The highest shoes rating DanceBrainz accepts. */
internal const val MAX_SHOES = 5

/** What a track row shows about shoes: the text under its title. */
internal data class TrackShoes(
    val line: String?,
)

/** The shoes text of one track row. */
internal fun trackShoes(
    mbid: String?,
    view: ShoesView?,
    signedIn: Boolean,
): TrackShoes {
    val summary = shoesSummary(view)
    return TrackShoes(line = listOfNotNull(summary, shoesReason(mbid, signedIn)).joinToString(" · ").ifEmpty { null })
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

/** Why a track cannot be edited now, or null when it can. Shown in the row, so it never looks editable by accident. */
internal fun shoesReason(
    mbid: String?,
    signedIn: Boolean,
): String? =
    when {
        mbid == null -> "Cannot be edited: no MusicBrainz ID"
        !signedIn -> "Sign in to DanceBrainz to edit"
        else -> null
    }

internal fun formatOneDecimal(value: Double): String = String.format(Locale.ROOT, "%.1f", value)
