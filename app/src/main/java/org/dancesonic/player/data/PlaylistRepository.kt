package org.dancesonic.player.data

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.dancesonic.player.data.db.DanceSonicDatabase
import org.dancesonic.player.data.db.PlaylistEntity
import org.dancesonic.player.data.db.PlaylistItemEntity
import org.dancesonic.player.data.db.PlaylistItemRow

/** A dance-type group inside a playlist. `danceType == null` is the "Ungrouped" group. */
data class PlaylistGroup(
    val danceType: String?,
    val items: List<PlaylistItemRow>,
)

/**
 * Course playlists are local to the device (cloud sync is out of scope for v1).
 * They are stored in Room, so they survive app restarts.
 */
class PlaylistRepository(
    private val db: DanceSonicDatabase,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    fun playlists(): Flow<List<PlaylistEntity>> = db.playlistDao().observeAll()

    fun playlist(id: Long): Flow<PlaylistEntity?> = db.playlistDao().observe(id)

    /** Items grouped by dance type, groups ordered by name with ungrouped last. */
    fun groups(playlistId: Long): Flow<List<PlaylistGroup>> =
        db.playlistItemDao().observeItems(playlistId).map { groupByDanceType(it) }

    suspend fun create(name: String): Long {
        val now = clock()
        return db.playlistDao().insert(
            PlaylistEntity(name = name.trim().ifBlank { "Untitled course" }, createdAt = now, updatedAt = now),
        )
    }

    suspend fun rename(playlistId: Long, name: String) {
        val current = db.playlistDao().get(playlistId) ?: return
        db.playlistDao().update(current.copy(name = name.trim().ifBlank { current.name }, updatedAt = clock()))
    }

    suspend fun deleteById(playlistId: Long) {
        db.playlistDao().get(playlistId)?.let { db.playlistDao().delete(it) }
    }

    /** Appends a track to the end of the playlist, in the given dance-type group. */
    suspend fun addTrack(playlistId: Long, trackId: String, danceType: String?) {
        db.withTransaction {
            val position = db.playlistItemDao().maxPosition(playlistId) + 1
            db.playlistItemDao().insert(
                PlaylistItemEntity(
                    playlistId = playlistId,
                    trackId = trackId,
                    danceType = danceType?.trim()?.ifBlank { null },
                    position = position,
                ),
            )
            touch(playlistId)
        }
    }

    suspend fun moveToGroup(item: PlaylistItemRow, danceType: String?) {
        val entity = db.playlistItemDao().get(item.id) ?: return
        db.playlistItemDao().update(entity.copy(danceType = danceType?.trim()?.ifBlank { null }))
        touch(item.playlistId)
    }

    suspend fun removeItem(item: PlaylistItemRow) {
        db.playlistItemDao().delete(item.id)
        touch(item.playlistId)
    }

    /** Track IDs of a playlist in play order. */
    suspend fun orderedTrackIds(playlistId: Long): List<String> =
        db.playlistItemDao().orderedTrackIds(playlistId)

    private suspend fun touch(playlistId: Long) {
        val current = db.playlistDao().get(playlistId) ?: return
        db.playlistDao().update(current.copy(updatedAt = clock()))
    }

    companion object {
        /** Pure grouping rule, separated for unit testing. */
        fun groupByDanceType(items: List<PlaylistItemRow>): List<PlaylistGroup> {
            val (ungrouped, grouped) = items.partition { it.danceType == null }
            // Group case-insensitively so "Waltz" and "waltz" share one group; display the first spelling.
            val named = grouped.groupBy { it.danceType!!.lowercase() }
                .toSortedMap()
                .map { (_, list) ->
                    val sorted = list.sortedBy { it.position }
                    PlaylistGroup(sorted.first().danceType, sorted)
                }
            return if (ungrouped.isEmpty()) named
            else named + PlaylistGroup(null, ungrouped.sortedBy { it.position })
        }
    }
}
