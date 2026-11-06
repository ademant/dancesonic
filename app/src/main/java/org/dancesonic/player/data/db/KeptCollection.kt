package org.dancesonic.player.data.db

import androidx.room.Entity

/**
 * An album or playlist the user keeps on the phone until it is unmarked. [kind] is one of [KeptKind]; [refId] is the
 * album id, or the playlist id as text. Stores the choice only: the tracks it covers are found when they are read.
 */
@Entity(tableName = "kept_collection", primaryKeys = ["kind", "refId"])
data class KeptCollectionEntity(
    val kind: String,
    val refId: String,
    val keptAt: Long,
)

/** The values of [KeptCollectionEntity.kind]. The SQL in [KeptCollectionDao] uses the same literals. */
object KeptKind {
    const val ALBUM = "album"
    const val PLAYLIST = "playlist"
}
