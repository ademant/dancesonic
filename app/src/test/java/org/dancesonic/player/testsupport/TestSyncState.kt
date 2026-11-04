package org.dancesonic.player.testsupport

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import org.dancesonic.player.data.SyncStateStore
import java.io.File
import java.util.UUID

/** A [SyncStateStore] backed by a fresh preferences file in the app cache, unique per call. */
fun newTestSyncStateStore(context: Context): SyncStateStore {
    val file = File(context.cacheDir, "sync-state-${UUID.randomUUID()}.preferences_pb")
    return SyncStateStore(PreferenceDataStoreFactory.create(produceFile = { file }))
}
