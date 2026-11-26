package org.dancesonic.player.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.dancesonic.player.data.db.AlbumEntity
import org.dancesonic.player.data.db.ArtistEntity
import org.dancesonic.player.data.db.DanceTypeCount
import org.dancesonic.player.data.db.KeptKind
import org.dancesonic.player.data.db.TrackWithTempo
import org.dancesonic.player.playback.PlayerController
import kotlin.random.Random

/** Library hierarchy: Artist → Release → Track. Back navigates up one level. */
@Composable
fun LibraryScreen(
    vm: LibraryViewModel,
    player: PlayerController,
    playlistVm: PlaylistViewModel,
    connectVm: ConnectViewModel,
    onOpenConfig: () -> Unit = {},
) {
    var artistId by rememberSaveable { mutableStateOf<String?>(null) }
    var albumId by rememberSaveable { mutableStateOf<String?>(null) }

    val openAlbum = albumId
    val openArtist = artistId
    BackHandler(enabled = openAlbum != null) { albumId = null }
    BackHandler(enabled = openAlbum == null && openArtist != null) { artistId = null }

    val artists by vm.artists.collectAsStateWithLifecycle()
    val artistName = artists.firstOrNull { it.id == openArtist }?.name
    val albumName by produceState<String?>(null, openAlbum) { value = openAlbum?.let { vm.album(it)?.name } }
    val crumbs =
        buildList {
            add(
                Crumb("Library", current = openArtist == null) {
                    artistId = null
                    albumId = null
                },
            )
            if (openArtist != null) add(Crumb(artistName ?: "Artist", current = openAlbum == null) { albumId = null })
            if (openAlbum != null) add(Crumb(albumName ?: "Album", current = true))
        }

    Column(Modifier.fillMaxSize()) {
        LibraryToolbar(connectVm, onOpenConfig)
        HorizontalDivider()
        // At the root the header already says "Library"; the path only helps once there is a level to go back to.
        if (openArtist != null) Breadcrumbs(crumbs)
        when {
            openAlbum != null ->
                key(openAlbum) {
                    val tracks by remember(openAlbum) { vm.tracks(openAlbum) }.collectAsStateWithLifecycle(emptyList())
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                        KeepOfflineToggle(KeptKind.ALBUM, openAlbum, vm.keptCollections)
                    }
                    TrackList(
                        tracks = tracks,
                        onPlay = { index -> player.play(tracks, index) },
                        playlistVm = playlistVm,
                        libraryVm = vm,
                        listState = keptListState(vm, "tracks:$openAlbum"),
                    )
                }
            openArtist != null ->
                key(openArtist) {
                    val albums by remember(
                        openArtist,
                    ) { vm.albums(openArtist) }.collectAsStateWithLifecycle(emptyList())
                    AlbumList(
                        albums,
                        onOpen = { albumId = it.id },
                        listState = keptListState(vm, "albums:$openArtist"),
                    )
                }
            else ->
                ArtistList(
                    artists,
                    onOpen = { artistId = it.id },
                    listState = keptListState(vm, "artists"),
                    sections = vm.artistSections,
                )
        }
    }
}

@Composable
private fun LibraryToolbar(
    connectVm: ConnectViewModel,
    onOpenConfig: () -> Unit,
) {
    LaunchedEffect(Unit) { connectVm.checkForChanges() }
    TabHeader("Library", onOpenConfig)
}

@Composable
private fun ArtistList(
    artists: List<ArtistEntity>,
    onOpen: (ArtistEntity) -> Unit,
    listState: LazyListState,
    sections: LetterSections,
) {
    if (artists.isEmpty()) {
        EmptyHint("No artists yet. Tap Refresh to sync from the server.")
        return
    }
    val groups = remember(artists) { groupByFirstLetter(artists) { it.name } }
    LetterGroupedList(
        groups = groups,
        listState = listState,
        sections = sections,
        key = { it.id },
        row = { artist -> ListRow(title = artist.name, onClick = { onOpen(artist) }) },
    )
}

@Composable
private fun AlbumList(
    albums: List<AlbumEntity>,
    onOpen: (AlbumEntity) -> Unit,
    listState: LazyListState,
) {
    if (albums.isEmpty()) {
        EmptyHint("No albums for this artist.")
        return
    }
    LazyColumn(state = listState) {
        items(albums, key = { it.id }) { album ->
            val subtitle = album.year?.toString()
            ListRow(title = album.name, subtitle = subtitle, onClick = { onOpen(album) })
        }
    }
}

@Composable
private fun TrackList(
    tracks: List<TrackWithTempo>,
    onPlay: (Int) -> Unit,
    playlistVm: PlaylistViewModel,
    libraryVm: LibraryViewModel,
    listState: LazyListState,
) {
    var addingTrack by remember { mutableStateOf<TrackWithTempo?>(null) }
    var previewing by remember { mutableStateOf<TrackWithTempo?>(null) }
    val shoesState by libraryVm.shoes.collectAsStateWithLifecycle()
    val signedIn by libraryVm.signedIn.collectAsStateWithLifecycle()
    LaunchedEffect(tracks) { libraryVm.refreshShoes(tracks.mapNotNull { it.track.mbid }) }
    if (tracks.isEmpty()) {
        EmptyHint("No tracks.")
        return
    }
    LazyColumn(state = listState) {
        items(tracks, key = { it.track.id }) { row ->
            TrackRow(
                row = row,
                shoes = trackShoes(row.track.mbid, row.track.mbid?.let { shoesState[it] }, signedIn),
                actions =
                    TrackRowActions(
                        onPlay = { onPlay(tracks.indexOf(row)) },
                        onInfo = { previewing = row },
                        onAddToPlaylist = { addingTrack = row },
                    ),
            )
        }
    }
    addingTrack?.let { row ->
        AddToPlaylistDialog(
            playlistVm = playlistVm,
            trackId = row.track.id,
            onDismiss = { addingTrack = null },
        )
    }
    previewing?.let { row ->
        TrackPreviewDialog(
            trackId = row.track.id,
            libraryVm = libraryVm,
            onPlay = { onPlay(tracks.indexOf(row)) },
            onDismiss = { previewing = null },
        )
    }
}

@Composable
fun DanceScreen(
    vm: LibraryViewModel,
    player: PlayerController,
    playlistVm: PlaylistViewModel,
    onOpenConfig: () -> Unit,
    // Starts a list at an index as the new queue. A parameter so tests can see what Play and a row tap start.
    play: (List<TrackWithTempo>, Int) -> Unit = player::play,
) {
    val danceTypes by vm.danceTypes.collectAsStateWithLifecycle()
    var danceType by rememberSaveable { mutableStateOf<String?>(null) }
    BackHandler(enabled = danceType != null) { danceType = null }

    var trackSort by rememberSaveable { mutableStateOf(TrackSort.ARTIST_TITLE) }
    var shuffleSeed by rememberSaveable { mutableLongStateOf(0L) }
    val selected = danceType
    val crumbs =
        buildList {
            add(Crumb("Dance", current = selected == null) { danceType = null })
            if (selected != null) add(Crumb(selected, current = true))
        }
    Column(Modifier.fillMaxSize()) {
        TabHeader("Dance", onOpenConfig)
        if (selected == null) {
            if (danceTypes.isEmpty()) {
                EmptyHint(
                    "No dance metadata yet. Tracks appear here once they match a DanceBrainz recording.",
                )
            } else {
                val listState = keptListState(vm, "dances")
                val groups = remember(danceTypes) { groupByFirstLetter(danceTypes) { it.danceType } }
                LetterGroupedList(
                    groups = groups,
                    listState = listState,
                    sections = vm.danceSections,
                    key = { it.danceType },
                    row = { entry: DanceTypeCount ->
                        ListRow(
                            title = entry.danceType,
                            subtitle = "${entry.trackCount} tracks",
                            onClick = { danceType = entry.danceType },
                        )
                    },
                )
            }
        } else {
            key(selected) {
                val tracks by remember(
                    selected,
                ) { vm.tracksForDance(selected) }.collectAsStateWithLifecycle(emptyList())
                val shown = remember(tracks, trackSort, shuffleSeed) { sortTracks(tracks, trackSort, shuffleSeed) }
                // The header names the tab; the path, Play and the sort only appear once a dance is open.
                DanceTracksBar(
                    crumbs = crumbs,
                    sort = trackSort,
                    canPlay = shown.isNotEmpty(),
                    onPlayAll = { play(shown, 0) },
                    onSort = { option ->
                        // Each pick of Randomize deals a new order.
                        if (option == TrackSort.RANDOM) shuffleSeed = Random.nextLong()
                        trackSort = option
                    },
                )
                TrackList(
                    tracks = shown,
                    onPlay = { index -> play(shown, index) },
                    playlistVm = playlistVm,
                    libraryVm = vm,
                    listState = keptListState(vm, "dance:$selected"),
                )
            }
        }
    }
}

/**
 * The bar over an open dance: the path back, then Play and the sort. Play always starts the list as shown from its
 * first track and makes it the queue, whatever the player holds now; it is off while the list is empty.
 */
@Composable
internal fun DanceTracksBar(
    crumbs: List<Crumb>,
    sort: TrackSort,
    canPlay: Boolean,
    onPlayAll: () -> Unit,
    onSort: (TrackSort) -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) { Breadcrumbs(crumbs) }
        TextButton(onClick = onPlayAll, enabled = canPlay) {
            Text("Play", style = MaterialTheme.typography.labelMedium)
        }
        TrackSortButton(sort, onSort)
    }
}

/** The sort of a dance's tracks, as a button that opens the orders. */
@Composable
private fun TrackSortButton(
    sort: TrackSort,
    onSort: (TrackSort) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }) {
            Text("Sort: ${sort.label}", style = MaterialTheme.typography.labelMedium)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            TrackSort.entries.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.label) },
                    onClick = {
                        open = false
                        onSort(option)
                    },
                )
            }
        }
    }
}

/**
 * The scroll state of one list, kept in [vm] under [key]. Going back to the list returns to the place it was left,
 * for as long as the app runs.
 */
@Composable
internal fun keptListState(
    vm: LibraryViewModel,
    key: String,
): LazyListState {
    val (index, offset) = vm.scrolls.position(key)
    val state = rememberLazyListState(index, offset)
    LaunchedEffect(state) {
        snapshotFlow { state.firstVisibleItemIndex to state.firstVisibleItemScrollOffset }
            .collect { (first, scroll) -> vm.scrolls.remember(key, first, scroll) }
    }
    return state
}

/** What a track row does: a tap plays it; its menu shows the info or adds it to a playlist. */
private class TrackRowActions(
    val onPlay: () -> Unit,
    val onInfo: () -> Unit,
    val onAddToPlaylist: () -> Unit,
)

/** The three dots that open a track's menu. */
private const val MORE_ACTIONS = "\u22EE"

@Composable
private fun TrackRow(
    row: TrackWithTempo,
    shoes: TrackShoes,
    actions: TrackRowActions,
) {
    val track = row.track
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().clickable(onClick = actions.onPlay).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(Modifier.weight(1f)) {
            Text(track.title, style = MaterialTheme.typography.bodyLarge)
            val tempo = row.tempoBpm?.let { " · $it BPM" }.orEmpty()
            val length = track.durationSec?.let { " · ${formatLength(it)}" }.orEmpty()
            Text(track.artistName + tempo + length, style = MaterialTheme.typography.bodySmall)
            shoes.line?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
        Box {
            IconButton(
                onClick = { menuOpen = true },
                modifier = Modifier.semantics { contentDescription = "More actions for ${track.title}" },
            ) {
                Text(MORE_ACTIONS, style = MaterialTheme.typography.titleMedium)
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text("Info") },
                    onClick = {
                        menuOpen = false
                        actions.onInfo()
                    },
                )
                DropdownMenuItem(
                    text = { Text("Add to playlist") },
                    onClick = {
                        menuOpen = false
                        actions.onAddToPlaylist()
                    },
                )
            }
        }
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
    /** What TalkBack says the tap does, when "activate" alone would not tell. */
    onClickLabel: String? = null,
    onClick: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClickLabel = onClickLabel, role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
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
