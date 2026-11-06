package org.dancesonic.player.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import org.dancesonic.player.data.PlaylistGroup
import org.dancesonic.player.data.db.KeptKind
import org.dancesonic.player.data.db.PlaylistItemRow

/** Course playlists: list, then a detail view grouped by dance type. */
@Composable
fun PlaylistsScreen(vm: PlaylistViewModel) {
    var openId by rememberSaveable { mutableStateOf<Long?>(null) }
    BackHandler(enabled = openId != null) { openId = null }

    val id = openId
    if (id == null) {
        PlaylistList(vm, onOpen = { openId = it })
    } else {
        PlaylistDetail(vm, playlistId = id, onBack = { openId = null })
    }
}

@Composable
private fun PlaylistList(
    vm: PlaylistViewModel,
    onOpen: (Long) -> Unit,
) {
    val playlists by vm.playlists.collectAsStateWithLifecycle()
    var newName by rememberSaveable { mutableStateOf("") }

    Column(Modifier.fillMaxSize()) {
        Text("Playlists", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(16.dp))
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = newName,
                onValueChange = { newName = it },
                label = { Text("New course name") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Button(onClick = {
                vm.create(newName) { id -> onOpen(id) }
                newName = ""
            }) { Text("Create") }
        }
        Spacer(Modifier.padding(4.dp))
        if (playlists.isEmpty()) {
            Text(
                "No playlists yet.",
                modifier = Modifier.padding(16.dp),
                style = MaterialTheme.typography.bodyMedium,
            )
        } else {
            LazyColumn {
                items(playlists, key = { it.id }) { playlist ->
                    ListRow(title = playlist.name, onClick = { onOpen(playlist.id) })
                }
            }
        }
    }
}

@Composable
private fun PlaylistDetail(
    vm: PlaylistViewModel,
    playlistId: Long,
    onBack: () -> Unit,
) {
    val playlist by remember(playlistId) { vm.playlist(playlistId) }.collectAsStateWithLifecycle(null)
    val groups by remember(playlistId) { vm.groups(playlistId) }.collectAsStateWithLifecycle(emptyList())
    var editing by remember { mutableStateOf<PlaylistItemRow?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }

    val current = playlist
    if (current == null) {
        // Deleted or not yet loaded.
        TextButton(onClick = onBack) { Text("Back") }
        return
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            TextButton(onClick = onBack) { Text("Back") }
            Text(current.name, style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = { confirmDelete = true }) { Text("Delete") }
        }
        Row(
            Modifier.padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(
                onClick = { vm.playAll(playlistId) },
                enabled = groups.isNotEmpty(),
            ) { Text("Play playlist") }
            KeepOfflineToggle(KeptKind.PLAYLIST, playlistId.toString(), vm.keptCollections)
        }
        HorizontalDivider(Modifier.padding(top = 8.dp))

        if (groups.isEmpty()) {
            Text(
                "Empty. Add tracks from the Library or Dance views.",
                modifier = Modifier.padding(16.dp),
                style = MaterialTheme.typography.bodyMedium,
            )
        } else {
            LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
                groups.forEach { group ->
                    item(key = "header-${group.danceType}") {
                        GroupHeader(group)
                    }
                    items(group.items, key = { it.id }) { item ->
                        PlaylistItemRowView(item = item, onEdit = { editing = item })
                    }
                }
            }
        }
    }

    editing?.let { item ->
        MoveDialog(
            item = item,
            onMove = { group ->
                vm.moveToGroup(item, group)
                editing = null
            },
            onRemove = {
                vm.removeItem(item)
                editing = null
            },
            onDismiss = { editing = null },
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete playlist?") },
            text = { Text("This removes the course from this device.") },
            confirmButton = {
                TextButton(onClick = {
                    vm.delete(playlistId)
                    confirmDelete = false
                    onBack()
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun GroupHeader(group: PlaylistGroup) {
    Text(
        text = group.danceType ?: "Ungrouped",
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun PlaylistItemRowView(
    item: PlaylistItemRow,
    onEdit: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(item.title ?: "Unavailable track", style = MaterialTheme.typography.bodyLarge)
            item.artistName?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
        TextButton(onClick = onEdit) { Text("Edit") }
    }
    HorizontalDivider()
}

@Composable
private fun MoveDialog(
    item: PlaylistItemRow,
    onMove: (String?) -> Unit,
    onRemove: () -> Unit,
    onDismiss: () -> Unit,
) {
    var group by rememberSaveable(item.id) { mutableStateOf(item.danceType.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(item.title ?: "Track") },
        text = {
            OutlinedTextField(
                value = group,
                onValueChange = { group = it },
                label = { Text("Dance-type group (blank = ungrouped)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { onMove(group.trim().ifBlank { null }) }) { Text("Save") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onRemove) { Text("Remove") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}
