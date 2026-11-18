package org.dancesonic.player.data

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import org.dancesonic.player.data.db.MIGRATION_13_14
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DanceLookupTempoMigrationTest {
    private fun version13(): SupportSQLiteOpenHelper {
        val config =
            SupportSQLiteOpenHelper.Configuration.builder(ApplicationProvider.getApplicationContext())
                .name(null)
                .callback(
                    object : SupportSQLiteOpenHelper.Callback(13) {
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            db.execSQL(
                                "CREATE TABLE `dance_lookup` (`mbid` TEXT NOT NULL, `checkedAt` INTEGER NOT NULL, " +
                                    "`bpm` INTEGER, `raters` INTEGER, `chaptersVersion` TEXT, `meter` TEXT, " +
                                    "`subMeter` TEXT, PRIMARY KEY(`mbid`))",
                            )
                            db.execSQL(
                                "CREATE TABLE `playlist` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                                    "`name` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL)",
                            )
                            db.execSQL(
                                "INSERT INTO dance_lookup VALUES ('rec-1', 5, 118, 4, 'v3', '8/4', '3+3+2')",
                            )
                            db.execSQL("INSERT INTO dance_lookup VALUES ('rec-2', 6, NULL, NULL, NULL, NULL, NULL)")
                            db.execSQL("INSERT INTO playlist (name, createdAt, updatedAt) VALUES ('Course', 1, 2)")
                        }

                        override fun onUpgrade(
                            db: SupportSQLiteDatabase,
                            oldVersion: Int,
                            newVersion: Int,
                        ) = Unit
                    },
                ).build()
        return FrameworkSQLiteOpenHelperFactory().create(config)
    }

    @Test
    fun `migration 13 to 14 drops the DanceBrainz tempo and keeps every other lookup value`() {
        version13().use {
            val db = it.writableDatabase
            MIGRATION_13_14.migrate(db)

            val columns = mutableListOf<String>()
            db.query("PRAGMA table_info(`dance_lookup`)").use { cursor ->
                while (cursor.moveToNext()) columns += cursor.getString(cursor.getColumnIndexOrThrow("name"))
            }
            assertEquals(listOf("mbid", "checkedAt", "chaptersVersion", "meter", "subMeter"), columns)

            db.query("SELECT checkedAt, chaptersVersion, meter, subMeter FROM dance_lookup WHERE mbid = 'rec-1'")
                .use { cursor ->
                    assertEquals(true, cursor.moveToFirst())
                    assertEquals(5L, cursor.getLong(0))
                    assertEquals("v3", cursor.getString(1))
                    assertEquals("8/4", cursor.getString(2))
                    assertEquals("3+3+2", cursor.getString(3))
                }
            db.query("SELECT checkedAt, meter FROM dance_lookup WHERE mbid = 'rec-2'").use { cursor ->
                assertEquals(true, cursor.moveToFirst())
                assertEquals(6L, cursor.getLong(0))
                assertNull(cursor.getString(1))
            }
        }
    }

    @Test
    fun `migration 13 to 14 leaves user data alone`() {
        version13().use {
            val db = it.writableDatabase
            MIGRATION_13_14.migrate(db)

            db.query("SELECT name FROM playlist").use { cursor ->
                assertEquals(true, cursor.moveToFirst())
                assertEquals("Course", cursor.getString(0))
            }
        }
    }
}
