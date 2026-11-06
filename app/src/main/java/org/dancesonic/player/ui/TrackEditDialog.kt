package org.dancesonic.player.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.dancesonic.player.dance.DanceSuggestion
import org.dancesonic.player.data.ShoesView
import org.dancesonic.player.data.db.EditEntity
import org.dancesonic.player.data.db.EditKind
import org.dancesonic.player.data.db.EditStatus

private val QUALIFIERS = listOf("learn", "disco", "both")
private val DIALOG_MAX_HEIGHT = 480.dp

/**
 * Edits one track on DanceBrainz: its shoes rating and its dances. Each change applies at once and is sent now or
 * later; the dialog stays open so the user sees whether it was sent.
 */
@Composable
internal fun TrackEditDialog(
    title: String,
    mbid: String,
    libraryVm: LibraryViewModel,
    onDismiss: () -> Unit,
) {
    val shoes by libraryVm.shoes.collectAsStateWithLifecycle()
    val queue by libraryVm.queue.collectAsStateWithLifecycle()
    val dances by remember(mbid) { libraryVm.danceNames(mbid) }.collectAsStateWithLifecycle(emptyList())
    val suggestions by libraryVm.suggestions.collectAsStateWithLifecycle()
    val suggestError by libraryVm.suggestError.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    var chosen by remember { mutableStateOf<DanceSuggestion?>(null) }
    var qualifier by rememberSaveable { mutableStateOf(QUALIFIERS.last()) }
    val danceChanges = queue.filter { it.mbid == mbid && it.kind != EditKind.SHOES }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.heightIn(max = DIALOG_MAX_HEIGHT).verticalScroll(rememberScrollState())) {
                ShoesSection(shoes[mbid], onRate = { libraryVm.rate(mbid, it) })
                HorizontalDivider()
                Text("Dances", style = MaterialTheme.typography.titleSmall)
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
                    Text(
                        "Suggestions need a connection. $it",
                        style = MaterialTheme.typography.bodySmall,
                    )
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
private fun ShoesSection(
    view: ShoesView?,
    onRate: (Int?) -> Unit,
) {
    Text("Shoes", style = MaterialTheme.typography.titleSmall)
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
        else -> "Change"
    }

/** Where a queued change stands, in words. */
internal fun changeStatus(change: EditEntity): String =
    when (change.status) {
        EditStatus.PENDING_REVIEW -> "Held for review by the DanceBrainz moderators"
        EditStatus.CONFLICT -> change.lastError.orEmpty()
        else -> change.lastError?.let { "Not sent: $it" } ?: "Waiting to be sent"
    }
