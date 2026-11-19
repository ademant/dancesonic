package org.dancesonic.player.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.dancesonic.player.dance.DanceSuggestion
import org.dancesonic.player.dance.meterLabel
import org.dancesonic.player.data.MeterView
import org.dancesonic.player.data.ShoesView
import org.dancesonic.player.data.db.EditEntity
import org.dancesonic.player.data.db.EditKind
import org.dancesonic.player.data.db.EditStatus

private val QUALIFIERS = listOf("learn", "disco", "both")

/** How each queue status shows on a row. */
private val STATUS_CHANGE =
    mapOf(
        EditStatus.QUEUED to FieldChange.QUEUED,
        EditStatus.PENDING_REVIEW to FieldChange.HELD,
        EditStatus.CONFLICT to FieldChange.CONFLICT,
    )

/** Most urgent first: what the user must act on, then what is still to be sent, then what waits for a moderator. */
private val FIELD_CHANGE_URGENCY = listOf(FieldChange.CONFLICT, FieldChange.QUEUED, FieldChange.HELD)
private val DIALOG_MAX_HEIGHT = 480.dp
private const val SHOE = "👟"

/** The dialogs a track's detail view can open. Shoes are rated in place and have none. */
private enum class DetailDialog { DANCES, METER, DESCRIPTION }

/**
 * The editable entries of one track in its detail view: dance, meter, the dance description when DanceBrainz has one,
 * and shoes. Changes apply at once and are sent now or later; the dialogs stay open so the user sees the send.
 */
@Composable
internal fun TrackDetailEditor(
    mbid: String,
    libraryVm: LibraryViewModel,
) {
    val shoes by libraryVm.shoes.collectAsStateWithLifecycle()
    val meters by libraryVm.meters.collectAsStateWithLifecycle()
    val signedIn by libraryVm.signedIn.collectAsStateWithLifecycle()
    val queue by libraryVm.queue.collectAsStateWithLifecycle()
    val dances by remember(mbid) { libraryVm.danceNames(mbid) }.collectAsStateWithLifecycle(emptyList())
    val hasDescription by produceState(false, mbid) { value = libraryVm.hasDanceDescription(mbid) }
    var open by rememberSaveable { mutableStateOf<DetailDialog?>(null) }
    TrackDetailRows(
        entries =
            TrackEntries(
                dances = dances,
                meter = meters[mbid],
                hasDescription = hasDescription,
                shoes = shoes[mbid],
                canEdit = signedIn,
                changes = fieldChanges(queue, mbid),
            ),
        actions =
            TrackEntryActions(
                onDances = { open = DetailDialog.DANCES },
                onMeter = { open = DetailDialog.METER },
                onDescription = { open = DetailDialog.DESCRIPTION },
                onRate = { libraryVm.rate(mbid, it) },
            ),
    )
    when (open) {
        DetailDialog.DANCES -> DanceDialog(mbid, libraryVm, onDismiss = { open = null })
        DetailDialog.METER -> MeterDialog(mbid, libraryVm, onDismiss = { open = null })
        DetailDialog.DESCRIPTION -> DescriptionDialog(mbid, libraryVm, onDismiss = { open = null })
        null -> Unit
    }
}

/** What the editable rows of a track show. */
internal class TrackEntries(
    val dances: List<String>,
    val meter: MeterView?,
    val hasDescription: Boolean,
    val shoes: ShoesView?,
    val canEdit: Boolean,
    /** Where each field's own unconfirmed change stands, shown on its row. */
    val changes: FieldChanges = FieldChanges(),
)

/** Where a field's latest change stands while DanceBrainz has not confirmed it. */
internal enum class FieldChange(val label: String) {
    /** Waiting in the offline queue. */
    QUEUED("not sent yet"),

    /** Sent, and held for review on a moderated server. */
    HELD("held for review"),

    /** Not sent, because the track changed on DanceBrainz in the meantime. */
    CONFLICT("changed on DanceBrainz, not sent"),
}

/** The state of the dance, meter and shoes changes of one track. Null for a field with no open change. */
internal data class FieldChanges(
    val dances: FieldChange? = null,
    val meter: FieldChange? = null,
    val shoes: FieldChange? = null,
)

/**
 * Sorts the queued changes of [mbid] by field (#75). When one field has several, the one the user must act on wins: a
 * conflict, then a change not yet sent, then one held for review.
 */
internal fun fieldChanges(
    queue: List<EditEntity>,
    mbid: String,
): FieldChanges {
    val mine = queue.filter { it.mbid == mbid }

    fun of(vararg kinds: String): FieldChange? {
        val found = mine.filter { it.kind in kinds }.mapNotNull { STATUS_CHANGE[it.status] }.toSet()
        return FIELD_CHANGE_URGENCY.firstOrNull { it in found }
    }

    return FieldChanges(
        dances = of(EditKind.DANCE_ADD, EditKind.DANCE_REMOVE),
        meter = of(EditKind.METER),
        shoes = of(EditKind.SHOES),
    )
}

/** What a tap on an editable row does. */
internal class TrackEntryActions(
    val onDances: () -> Unit = {},
    val onMeter: () -> Unit = {},
    val onDescription: () -> Unit = {},
    val onRate: (Int) -> Unit = {},
)

/**
 * The editable rows of a track, in this order: dance, meter, the dance description (only when there is one), and shoes.
 * Every row has the same font size. A dance or meter row opens its dialog; the description row opens the text
 * read-only; the shoes are rated in place. Without a sign-in the rows that change data are off.
 */
@Composable
internal fun TrackDetailRows(
    entries: TrackEntries,
    actions: TrackEntryActions,
) {
    val changes = entries.changes
    val danceText = if (entries.dances.isEmpty()) "none assigned" else entries.dances.joinToString(", ")
    val meterText = meterLabel(entries.meter?.meter, entries.meter?.subMeter) ?: "not set"
    // A meter change not yet in the queue rows (the view marks it pending) still reads as not sent.
    val meterChange = changes.meter ?: FieldChange.QUEUED.takeIf { entries.meter?.pending == true }
    val shoesChange = changes.shoes ?: FieldChange.QUEUED.takeIf { entries.shoes?.pending == true }
    Column(Modifier.fillMaxWidth()) {
        EntryRow(withChange("Dance: $danceText", changes.dances), enabled = entries.canEdit, onClick = actions.onDances)
        EntryRow(withChange("Meter: $meterText", meterChange), enabled = entries.canEdit, onClick = actions.onMeter)
        if (entries.hasDescription) EntryRow("Dance description", enabled = true, onClick = actions.onDescription)
        ShoesRow(entries.shoes, shoesChange, canEdit = entries.canEdit, onRate = actions.onRate)
        shoesMedianText(entries.shoes)?.let { EntryRow(it, enabled = false, onClick = {}) }
    }
}

/** [text] with the state of its field's open change after it, such as `Dance: gavotte · held for review`. */
internal fun withChange(
    text: String,
    change: FieldChange?,
): String = if (change == null) text else "$text · ${change.label}"

/** The public shoes median of a track, such as `Median 3.5 from 12 raters`, or null when nobody has rated it. */
internal fun shoesMedianText(view: ShoesView?): String? {
    if (view == null) return null
    val median = view.median ?: return null
    return "Median ${formatOneDecimal(median)} from ${view.raters} raters"
}

@Composable
private fun EntryRow(
    text: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Text(
        text,
        style = MaterialTheme.typography.bodyLarge,
        color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick).padding(vertical = 8.dp),
    )
}

/** Five shoes; the ones up to the viewer's rating are lit, the rest empty. Tapping one sets the rating to it. */
@Composable
private fun ShoesRow(
    shoes: ShoesView?,
    change: FieldChange?,
    canEdit: Boolean,
    onRate: (Int) -> Unit,
) {
    val mine = shoes?.mine ?: 0
    val label = if (change == null) "Shoes:" else "Shoes (${change.label}):"
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        for (value in 1..MAX_SHOES) {
            TextButton(onClick = { onRate(value) }, enabled = canEdit) {
                Text(
                    SHOE,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (value <= mine) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                )
            }
        }
    }
}

/** Assigned dances can be removed and new ones added. Each change is shown until it is sent. */
@Composable
private fun DanceDialog(
    mbid: String,
    libraryVm: LibraryViewModel,
    onDismiss: () -> Unit,
) {
    val queue by libraryVm.queue.collectAsStateWithLifecycle()
    val dances by remember(mbid) { libraryVm.danceNames(mbid) }.collectAsStateWithLifecycle(emptyList())
    val suggestions by libraryVm.suggestions.collectAsStateWithLifecycle()
    val suggestError by libraryVm.suggestError.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    var chosen by remember { mutableStateOf<DanceSuggestion?>(null) }
    var qualifier by rememberSaveable { mutableStateOf(QUALIFIERS.last()) }
    val danceChanges = queue.filter { it.mbid == mbid && it.kind != EditKind.SHOES && it.kind != EditKind.METER }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Dances") },
        text = {
            Column(Modifier.heightIn(max = DIALOG_MAX_HEIGHT).verticalScroll(rememberScrollState())) {
                if (dances.isEmpty()) {
                    Text("No dances on DanceBrainz yet, or not looked up.")
                }
                dances.forEach { name ->
                    val removalQueued = danceChanges.any { it.kind == EditKind.DANCE_REMOVE && it.danceName == name }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(name, Modifier.weight(1f))
                        if (removalQueued) {
                            Text("Removal queued", style = MaterialTheme.typography.bodySmall)
                        } else {
                            TextButton(onClick = { libraryVm.removeDance(mbid, name) }) { Text("Remove") }
                        }
                    }
                }
                danceChanges.forEach { change ->
                    ChangeRow(change, onDismiss = { libraryVm.dismissChange(change.id) })
                }
                if (queue.any { it.status == EditStatus.QUEUED }) {
                    TextButton(onClick = libraryVm::retryChanges) { Text("Send again") }
                }
                HorizontalDivider()
                Text("Add a dance", style = MaterialTheme.typography.titleSmall)
                OutlinedTextField(
                    value = query,
                    onValueChange = {
                        query = it
                        chosen = null
                        libraryVm.suggestDances(it)
                    },
                    label = { Text("Dance name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                suggestError?.let {
                    Text("Suggestions need a connection. $it", style = MaterialTheme.typography.bodySmall)
                }
                suggestions.forEach { suggestion ->
                    TextButton(onClick = { chosen = suggestion }) {
                        Text(if (chosen == suggestion) "✓ ${suggestion.path}" else suggestion.path)
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    QUALIFIERS.forEach { option ->
                        FilterChip(
                            selected = option == qualifier,
                            onClick = { qualifier = option },
                            label = { Text(option) },
                        )
                    }
                }
                TextButton(
                    enabled = chosen != null,
                    onClick = {
                        chosen?.let { libraryVm.addDance(mbid, it, qualifier) }
                        query = ""
                        chosen = null
                        libraryVm.suggestDances("")
                    },
                ) {
                    Text("Add dance")
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

@Composable
internal fun ChangeRow(
    change: EditEntity,
    onDismiss: () -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(changeLine(change))
            Text(changeStatus(change), style = MaterialTheme.typography.bodySmall)
        }
        TextButton(onClick = onDismiss) { Text("Dismiss") }
    }
}

/** What a queued change does, in words. */
internal fun changeLine(change: EditEntity): String =
    when (change.kind) {
        EditKind.DANCE_ADD -> "Add ${change.path.orEmpty()} (${change.qualifier.orEmpty()})"
        EditKind.DANCE_REMOVE -> "Remove ${change.danceName.orEmpty()}"
        EditKind.SHOES -> change.value?.let { "Shoes rating $it" } ?: "Clear shoes rating"
        EditKind.METER -> "Meter ${meterChangeLabel(change)}"
        else -> "Change"
    }

/** The meter a queued meter change sets, such as `8/4 - 3+3+2`. */
internal fun meterChangeLabel(change: EditEntity): String {
    val parent = change.meterParent
    return meterLabel(parent ?: change.path, if (parent != null) change.path else null).orEmpty()
}

/** Where a queued change stands, in words. */
internal fun changeStatus(change: EditEntity): String =
    when (change.status) {
        EditStatus.PENDING_REVIEW -> "Held for review by the DanceBrainz moderators"
        EditStatus.CONFLICT -> change.lastError.orEmpty()
        else -> change.lastError?.let { "Not sent: $it" } ?: "Waiting to be sent"
    }
