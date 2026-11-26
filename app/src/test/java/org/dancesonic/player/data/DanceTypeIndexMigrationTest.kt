package org.dancesonic.player.data

import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.dancesonic.player.data.db.DanceSonicDatabase
import org.dancesonic.player.data.db.MIGRATION_13_14
import org.dancesonic.player.data.db.MIGRATION_15_16
import org.dancesonic.player.data.db.PlaylistEntity
import org.dancesonic.player.data.db.PlaylistItemEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The dance type index (#138), a rebuild that reruns after an interrupted attempt, and foreign keys (#127). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DanceTypeIndexMigrationTest {
    private fun databaseAt(
        version: Int,
        create: (SupportSQLiteDatabase) -> Unit,
    ): SupportSQLiteOpenHelper =
        FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration
                .builder(ApplicationProvider.getApplicationContext())
                .name(null)
                .callback(
                    object : SupportSQLiteOpenHelper.Callback(version) {
                        override fun onCreate(db: SupportSQLiteDatabase) = create(db)

                        override fun onUpgrade(
                            db: SupportSQLiteDatabase,
                            oldVersion: Int,
                            newVersion: Int,
                        ) = Unit
                    },
                ).build(),
        )

    @Test
    fun `migration 15 to 16 indexes the dance type and keeps the dance data`() {
        databaseAt(15) { db ->
            db.execSQL(
                "CREATE TABLE `dance_metadata` (`mbid` TEXT NOT NULL, `danceType` TEXT NOT NULL, " +
                    "`fetchedAt` INTEGER NOT NULL, PRIMARY KEY(`mbid`, `danceType`))",
            )
            db.execSQL("INSERT INTO dance_metadata VALUES ('m-1', 'Waltz', 5)")
        }.use { helper ->
            val db = helper.writableDatabase
            MIGRATION_15_16.migrate(db)

            db.query("PRAGMA index_list(`dance_metadata`)").use { cursor ->
                val names = buildList { while (cursor.moveToNext()) add(cursor.getString(1)) }
                assertTrue(names.toString(), "index_dance_metadata_danceType" in names)
            }
            db.query("EXPLAIN QUERY PLAN SELECT mbid FROM dance_metadata WHERE danceType = 'Waltz'").use { cursor ->
                val plan = buildList { while (cursor.moveToNext()) add(cursor.getString(3)) }.joinToString()
                assertTrue(plan, plan.contains("index_dance_metadata_danceType"))
            }
            db.query("SELECT danceType FROM dance_metadata WHERE mbid = 'm-1'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("Waltz", cursor.getString(0))
            }
        }
    }

    @Test
    fun `migration 13 to 14 reruns after an attempt that stopped before the rename`() {
        databaseAt(13) { db ->
            db.execSQL(
                "CREATE TABLE `dance_lookup` (`mbid` TEXT NOT NULL, `checkedAt` INTEGER NOT NULL, " +
                    "`chaptersVersion` TEXT, `meter` TEXT, `subMeter` TEXT, `bpm` INTEGER, `raters` INTEGER, " +
                    "PRIMARY KEY(`mbid`))",
            )
            db.execSQL("INSERT INTO dance_lookup VALUES ('m-1', 7, 'v1', '3/4', NULL, 90, 2)")
            // What an interrupted run leaves: the copy exists, with a row in it already.
            db.execSQL("CREATE TABLE `dance_lookup_new` (`mbid` TEXT NOT NULL, PRIMARY KEY(`mbid`))")
            db.execSQL("INSERT INTO dance_lookup_new VALUES ('m-1')")
        }.use { helper ->
            val db = helper.writableDatabase
            MIGRATION_13_14.migrate(db)

            db.query("SELECT mbid, checkedAt, chaptersVersion, meter FROM dance_lookup").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("m-1", cursor.getString(0))
                assertEquals(7L, cursor.getLong(1))
                assertEquals("v1", cursor.getString(2))
                assertEquals("3/4", cursor.getString(3))
                assertEquals(1, cursor.count)
            }
        }
    }

    @Test
    fun `deleting a playlist removes its items through the foreign key, without an extra callback`() =
        runBlocking<Unit> {
            val db =
                Room
                    .inMemoryDatabaseBuilder(
                        ApplicationProvider.getApplicationContext(),
                        DanceSonicDatabase::class.java,
                    ).build()
            try {
                val id = db.playlistDao().insert(PlaylistEntity(name = "Course", createdAt = 1, updatedAt = 1))
                db.playlistItemDao().insert(
                    PlaylistItemEntity(playlistId = id, trackId = "s-1", danceType = null, position = 0),
                )
                val playlist = checkNotNull(db.playlistDao().get(id))

                db.playlistDao().delete(playlist)

                assertEquals(emptyList<String>(), db.playlistItemDao().orderedTrackIds(id))
            } finally {
                db.close()
            }
        }
}
