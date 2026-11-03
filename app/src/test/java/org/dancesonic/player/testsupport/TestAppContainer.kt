package org.dancesonic.player.testsupport

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.dancesonic.player.AppContainer
import org.dancesonic.player.data.db.AlbumEntity
import org.dancesonic.player.data.db.ArtistEntity
import org.dancesonic.player.data.db.TrackEntity
import java.io.File
import java.util.UUID

/**
 * A real [AppContainer] for Robolectric tests: Room on disk, DataStore in a unique file per call.
 * Android Keystore is not available under Robolectric, so tests here never save server settings.
 */
internal fun newTestContainer(): AppContainer {
    val context: Context = ApplicationProvider.getApplicationContext()
    val dataStore =
        PreferenceDataStoreFactory.create(
            produceFile = { File(context.cacheDir, "test-${UUID.randomUUID()}.preferences_pb") },
        )
    return AppContainer(context, dataStore)
}

/** Empties every table. Room's clearAllTables is blocking, so it runs off the test thread. */
internal suspend fun AppContainer.resetDatabase() {
    withContext(Dispatchers.IO) { database.clearAllTables() }
}

/** One artist, one album and one track with a DanceBrainz-matchable recording ID. */
internal suspend fun AppContainer.seedOneAlbum() {
    database.artistDao().upsertAll(listOf(ArtistEntity(id = "ar-1", name = "Ada", syncedAt = 1L)))
    database.albumDao().upsertAll(
        listOf(AlbumEntity(id = "al-1", artistId = "ar-1", name = "Night", year = 1999, syncedAt = 1L)),
    )
    database.trackDao().upsertAll(
        listOf(
            TrackEntity(
                id = "s-1",
                albumId = "al-1",
                artistName = "Ada",
                title = "One",
                trackNumber = 1,
                durationSec = 200,
                mbid = "rec-1",
                syncedAt = 1L,
            ),
        ),
    )
}
