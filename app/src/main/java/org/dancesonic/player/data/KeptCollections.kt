package org.dancesonic.player.data

import kotlinx.coroutines.flow.Flow
import org.dancesonic.player.data.db.DanceSonicDatabase
import org.dancesonic.player.data.db.KeptCollectionEntity

/**
 * Which albums and playlists the user keeps on the phone. Unmarking one only stops the downloads: its files then
 * count as ordinary cache until they are played over or evicted. The downloads follow the stored choices by
 * themselves, see [org.dancesonic.player.playback.OfflineDownloads].
 */
class KeptCollections(
    private val database: DanceSonicDatabase,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** True while the album or playlist [refId] of [kind] is kept. */
    fun observe(
        kind: String,
        refId: String,
    ): Flow<Boolean> = database.keptCollectionDao().observeKept(kind, refId)

    suspend fun set(
        kind: String,
        refId: String,
        keep: Boolean,
    ) {
        val dao = database.keptCollectionDao()
        if (keep) {
            dao.keep(KeptCollectionEntity(kind = kind, refId = refId, keptAt = clock()))
        } else {
            dao.unkeep(kind, refId)
        }
    }

    /** Forgets every kept choice, for a connection to another server, or a sign-out. */
    suspend fun forgetAll() {
        database.keptCollectionDao().deleteAll()
    }
}
