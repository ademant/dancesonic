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
import org.dancesonic.player.dance.DanceBrainzException
import org.dancesonic.player.dance.DanceSuggestion
import org.dancesonic.player.dance.MeterValue
import org.dancesonic.player.dance.meterLabel
import org.dancesonic.player.dance.subMetersOf
import org.dancesonic.player.dance.topMeters
import org.dancesonic.player.data.MeterView
import org.dancesonic.player.data.ShoesView
import org.dancesonic.player.data.db.EditEntity
import org.dancesonic.player.data.db.EditKind
import org.dancesonic.player.data.db.EditStatus

private val QUALIFIERS = listOf("learn", "disco", "both")
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
)

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
    val danceText = if (entries.dances.isEmpty()) "none assigned" else entries.dances.joinToString(", ")
    val label = meterLabel(entries.meter?.meter, entries.meter?.subMeter)
    val meterText =
        when {
            label == null -> "not set"
            entries.meter?.pending == true -> "$label · not sent yet"
            else -> label
        }
    Column(Modifier.fillMaxWidth()) {
        EntryRow("Dance: $danceText", enabled = entries.canEdit, onClick = actions.onDances)
        EntryRow("Meter: $meterText", enabled = entries.canEdit, onClick = actions.onMeter)
        if (entries.hasDescription) EntryRow("Dance description", enabled = true, onClick = actions.onDescription)
        ShoesRow(entries.shoes, canEdit = entries.canEdit, onRate = actions.onRate)
        shoesMedianText(entries.shoes)?.let { EntryRow(it, enabled = false, onClick = {}) }
    }
}

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
    canEdit: Boolean,
    onRate: (Int) -> Unit,
) {
    val mine = shoes?.mine ?: 0
    val label = if (shoes?.pending == true) "Shoes (not sent yet):" else "Shoes:"
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

/**
 * Sets the track's meter, and optionally a sub-meter under it. The meter is shown as it is now; a change is queued
 * and sent like the others. The sub-meter is optional.
 */
@Composable
private fun MeterDialog(
    mbid: String,
    libraryVm: LibraryViewModel,
    onDismiss: () -> Unit,
) {
    val meters by libraryVm.meters.collectAsStateWithLifecycle()
    val queue by libraryVm.queue.collectAsStateWithLifecycle()
    val current = meters[mbid]
    val options by produceState(MeterOptions(null, null), mbid) {
        value =
            try {
                MeterOptions(libraryVm.meterOptions(), error = null)
            } catch (e: DanceBrainzException) {
                MeterOptions(null, error = e.message)
            }
    }
    var meterName by rememberSaveable { mutableStateOf(current?.meter) }
    var subName by rememberSaveable { mutableStateOf(current?.subMeter) }
    val values = options.values
    val changed = meterName != current?.meter || subName != current?.subMeter
    val meterChanges = queue.filter { it.mbid == mbid && it.kind == EditKind.METER }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Meter") },
        text = {
            Column(Modifier.heightIn(max = DIALOG_MAX_HEIGHT).verticalScroll(rememberScrollState())) {
                when {
                    options.error != null -> Text("Meters need a connection. ${options.error}")
                    values == null -> Text("Loading meters…")
                    else ->
                        MeterChoices(
                            values = values,
                            meterName = meterName,
                            subName = subName,
                            onMeter = { meter ->
                                meterName = meter.name
                                subName = null
                            },
                            onSub = { subName = it },
                        )
                }
                meterChanges.forEach { change ->
                    ChangeRow(change, onDismiss = { libraryVm.dismissChange(change.id) })
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = values != null && meterName != null && changed,
                onClick = {
                    val meter = meterName ?: return@TextButton
                    val sub = subName
                    if (sub != null) {
                        libraryVm.setMeter(mbid, sub, parentName = meter)
                    } else {
                        libraryVm.setMeter(mbid, meter, parentName = null)
                    }
                },
            ) {
                Text("Set meter")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

/** The meters DanceBrainz offers, and the sub-meters under the chosen one. */
@Composable
private fun MeterChoices(
    values: List<MeterValue>,
    meterName: String?,
    subName: String?,
    onMeter: (MeterValue) -> Unit,
    onSub: (String?) -> Unit,
) {
    val meters = topMeters(values)
    if (meters.isEmpty()) Text("DanceBrainz has no meters yet.")
    meters.forEach { meter ->
        TextButton(onClick = { onMeter(meter) }) {
            Text(if (meter.name == meterName) "✓ ${meter.name}" else meter.name)
        }
    }
    val chosen = meters.firstOrNull { it.name == meterName } ?: return
    HorizontalDivider()
    Text("Sub-meter", style = MaterialTheme.typography.titleSmall)
    TextButton(onClick = { onSub(null) }) { Text(if (subName == null) "✓ No sub-meter" else "No sub-meter") }
    subMetersOf(values, chosen).forEach { sub ->
        TextButton(onClick = { onSub(sub.name) }) {
            Text(if (sub.name == subName) "✓ ${sub.name}" else sub.name)
        }
    }
}

/** The meter options loaded for the dialog: either the values, or why they could not be loaded. */
private class MeterOptions(
    val values: List<MeterValue>?,
    val error: String?,
)

/** The stored dance description, shown read-only. [loaded] is false while it is being fetched. */
private class DescriptionState(
    val loaded: Boolean,
    val text: String?,
    val error: String?,
)

@Composable
private fun DescriptionDialog(
    mbid: String,
    libraryVm: LibraryViewModel,
    onDismiss: () -> Unit,
) {
    val state by produceState(DescriptionState(loaded = false, text = null, error = null), mbid) {
        value =
            try {
                DescriptionState(loaded = true, text = libraryVm.danceDescription(mbid), error = null)
            } catch (e: DanceBrainzException) {
                DescriptionState(loaded = true, text = null, error = e.message)
            }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Dance description") },
        text = {
            Column(Modifier.heightIn(max = DIALOG_MAX_HEIGHT).verticalScroll(rememberScrollState())) {
                when {
                    !state.loaded -> Text("Loading…")
                    state.error != null -> Text("The description could not be loaded. ${state.error}")
                    state.text == null -> Text("No description is stored.")
                    else -> Text(state.text.orEmpty())
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun ChangeRow(
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
