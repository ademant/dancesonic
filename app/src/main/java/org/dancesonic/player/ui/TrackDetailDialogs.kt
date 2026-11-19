package org.dancesonic.player.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.dancesonic.player.dance.DanceBrainzException
import org.dancesonic.player.dance.MeterValue
import org.dancesonic.player.dance.subMetersOf
import org.dancesonic.player.dance.topMeters
import org.dancesonic.player.data.db.EditKind

private val DIALOG_MAX_HEIGHT = 480.dp

/**
 * Sets the track's meter, and optionally a sub-meter under it. The meter is shown as it is now; a change is queued
 * and sent like the others. The sub-meter is optional.
 */
@Composable
internal fun MeterDialog(
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
internal fun DescriptionDialog(
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
