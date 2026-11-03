package org.dancesonic.player.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
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
import org.dancesonic.player.data.db.AlbumEntity
import org.dancesonic.player.data.db.ArtistEntity
import org.dancesonic.player.data.db.DanceTypeCount
import org.dancesonic.player.data.db.TrackEntity
import org.dancesonic.player.playback.PlayerController

/** Library hierarchy: Artist → Release → Track. Back navigates up one level. */
@Composable
fun LibraryScreen(
    vm: LibraryViewModel,
    player: PlayerController,
    playlistVm: PlaylistViewModel,
    connectVm: ConnectViewModel,
) {
    var artistId by rememberSaveable { mutableStateOf<String?>(null) }
    var albumId by rememberSaveable { mutableStateOf<String?>(null) }

    val openAlbum = albumId
    val openArtist = artistId
    BackHandler(enabled = openAlbum != null) { albumId = null }
    BackHandler(enabled = openAlbum == null && openArtist != null) { artistId = null }

    Column(Modifier.fillMaxSize()) {
        LibraryToolbar(connectVm)
        HorizontalDivider()
        when {
            openAlbum != null -> {
                val tracks by remember(openAlbum) { vm.tracks(openAlbum) }.collectAsStateWithLifecycle(emptyList())
                TrackList(
                    tracks = tracks,
                    onPlay = { index -> player.play(tracks, index) },
                    playlistVm = playlistVm,
                )
            }
            openArtist != null -> {
                val albums by remember(openArtist) { vm.albums(openArtist) }.collectAsStateWithLifecycle(emptyList())
                AlbumList(albums, onOpen = { albumId = it.id })
            }
            else -> {
                val artists by vm.artists.collectAsStateWithLifecycle()
                ArtistList(artists, onOpen = { artistId = it.id })
            }
        }
    }
}

@Composable
private fun LibraryToolbar(connectVm: ConnectViewModel) {
    val state by connectVm.state.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxWidth().padding(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Library", style = MaterialTheme.typography.titleMedium)
            Row {
                OutlinedButton(onClick = { connectVm.sync() }, enabled = !state.busy) { Text("Refresh") }
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = { connectVm.disconnect() }) { Text("Disconnect") }
            }
        }
        if (state.busy) {
            val progress = state.syncProgress
            val text =
                if (progress == null || progress.total == 0) {
                    "Syncing…"
                } else {
                    "Syncing ${progress.done} of ${progress.total} albums…"
                }
            Text(text, style = MaterialTheme.typography.bodySmall)
        }
        state.lastSync?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        state.error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun ArtistList(
    artists: List<ArtistEntity>,
    onOpen: (ArtistEntity) -> Unit,
) {
    if (artists.isEmpty()) {
        EmptyHint("No artists yet. Tap Refresh to sync from the server.")
        return
    }
    LazyColumn(contentPadding = PaddingValues(vertical = 4.dp)) {
        items(artists, key = { it.id }) { artist ->
            ListRow(title = artist.name, onClick = { onOpen(artist) })
        }
    }
}

@Composable
private fun AlbumList(
    albums: List<AlbumEntity>,
    onOpen: (AlbumEntity) -> Unit,
) {
    if (albums.isEmpty()) {
        EmptyHint("No albums for this artist.")
        return
    }
    LazyColumn {
        items(albums, key = { it.id }) { album ->
            val subtitle = album.year?.toString()
            ListRow(title = album.name, subtitle = subtitle, onClick = { onOpen(album) })
        }
    }
}

@Composable
private fun TrackList(
    tracks: List<TrackEntity>,
    onPlay: (Int) -> Unit,
    playlistVm: PlaylistViewModel,
) {
    var addingTrack by remember { mutableStateOf<TrackEntity?>(null) }
    if (tracks.isEmpty()) {
        EmptyHint("No tracks.")
        return
    }
    LazyColumn {
        items(tracks, key = { it.id }) { track ->
            TrackRow(
                track = track,
                onPlay = { onPlay(tracks.indexOf(track)) },
                onAddToPlaylist = { addingTrack = track },
            )
        }
    }
    addingTrack?.let { track ->
        AddToPlaylistDialog(
            playlistVm = playlistVm,
            trackId = track.id,
            onDismiss = { addingTrack = null },
        )
    }
}

@Composable
fun DanceScreen(
    vm: LibraryViewModel,
    player: PlayerController,
    playlistVm: PlaylistViewModel,
) {
    val danceTypes by vm.danceTypes.collectAsStateWithLifecycle()
    var danceType by rememberSaveable { mutableStateOf<String?>(null) }
    BackHandler(enabled = danceType != null) { danceType = null }

    val selected = danceType
    if (selected == null) {
        Column(Modifier.fillMaxSize()) {
            Text(
                "Dance",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(16.dp),
            )
            if (danceTypes.isEmpty()) {
                EmptyHint(
                    "No dance metadata yet. Tracks appear here once they match a DanceBrainz recording.",
                )
            } else {
                LazyColumn {
                    items(danceTypes, key = { it.danceType }) { entry: DanceTypeCount ->
                        ListRow(
                            title = entry.danceType,
                            subtitle = "${entry.trackCount} tracks",
                            onClick = { danceType = entry.danceType },
                        )
                    }
                }
            }
        }
    } else {
        val tracks by remember(selected) { vm.tracksForDance(selected) }.collectAsStateWithLifecycle(emptyList())
        Column(Modifier.fillMaxSize()) {
            Text(selected, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(16.dp))
            TrackList(
                tracks = tracks,
                onPlay = { index -> player.play(tracks, index) },
                playlistVm = playlistVm,
            )
        }
    }
}

@Composable
private fun TrackRow(
    track: TrackEntity,
    onPlay: () -> Unit,
    onAddToPlaylist: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onPlay).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(Modifier.weight(1f)) {
            Text(track.title, style = MaterialTheme.typography.bodyLarge)
            Text(track.artistName, style = MaterialTheme.typography.bodySmall)
        }
        TextButton(onClick = onAddToPlaylist) { Text("+ Playlist") }
    }
    HorizontalDivider()
}

@Composable
fun AddToPlaylistDialog(
    playlistVm: PlaylistViewModel,
    trackId: String,
    onDismiss: () -> Unit,
) {
    val playlists by playlistVm.playlists.collectAsStateWithLifecycle()
    var group by rememberSaveable { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add to playlist") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = group,
                    onValueChange = { group = it },
                    label = { Text("Dance-type group (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (playlists.isEmpty()) {
                    Text("Create a playlist in the Playlists tab first.")
                }
                playlists.forEach { playlist ->
                    Button(
                        onClick = {
                            playlistVm.addTrack(playlist.id, trackId, group.trim().ifBlank { null })
                            onDismiss()
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(playlist.name) }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
internal fun ListRow(
    title: String,
    subtitle: String? = null,
    onClick: () -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
    HorizontalDivider()
}

@Composable
internal fun EmptyHint(text: String) {
    Column(Modifier.fillMaxWidth().padding(24.dp)) {
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}
