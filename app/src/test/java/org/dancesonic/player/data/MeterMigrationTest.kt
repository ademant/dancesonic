package org.dancesonic.player.data

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import org.dancesonic.player.data.db.MIGRATION_11_12
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MeterMigrationTest {
    @Test
    fun `migration 11 to 12 adds the meter columns and keeps the rows that exist`() {
        val config =
            SupportSQLiteOpenHelper.Configuration.builder(ApplicationProvider.getApplicationContext())
                .name(null)
                .callback(
                    object : SupportSQLiteOpenHelper.Callback(11) {
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            db.execSQL(
                                "CREATE TABLE `dance_lookup` (`mbid` TEXT NOT NULL, `checkedAt` INTEGER NOT NULL, " +
                                    "`bpm` INTEGER, `raters` INTEGER, `chaptersVersion` TEXT, PRIMARY KEY(`mbid`))",
                            )
                            db.execSQL(
                                "CREATE TABLE `edit_queue` (`id` INTEGER PRIMARY KEY AUTOINCREMENT, " +
                                    "`kind` TEXT NOT NULL, `mbid` TEXT NOT NULL, `value` INTEGER, `danceName` TEXT, " +
                                    "`path` TEXT, `qualifier` TEXT, `expectedDances` TEXT, " +
                                    "`status` TEXT NOT NULL, `queuedAt` INTEGER NOT NULL, `lastError` TEXT)",
                            )
                            db.execSQL("INSERT INTO dance_lookup (mbid, checkedAt) VALUES ('m-1', 5)")
                        }

                        override fun onUpgrade(
                            db: SupportSQLiteDatabase,
                            oldVersion: Int,
                            newVersion: Int,
                        ) = Unit
                    },
                ).build()
        val helper = FrameworkSQLiteOpenHelperFactory().create(config)
        helper.use {
            val db = it.writableDatabase
            MIGRATION_11_12.migrate(db)

            db.execSQL("UPDATE dance_lookup SET meter = '8/4', subMeter = '3+3+2' WHERE mbid = 'm-1'")
            db.execSQL(
                "INSERT INTO edit_queue (kind, mbid, path, meterParent, status, queuedAt) " +
                    "VALUES ('meter', 'm-1', '3+3+2', '8/4', 'queued', 9)",
            )

            db.query("SELECT meter, subMeter FROM dance_lookup WHERE mbid = 'm-1'").use { cursor ->
                assertEquals(true, cursor.moveToFirst())
                assertEquals("8/4", cursor.getString(0))
                assertEquals("3+3+2", cursor.getString(1))
            }
            db.query("SELECT meterParent FROM edit_queue WHERE kind = 'meter'").use { cursor ->
                assertEquals(true, cursor.moveToFirst())
                assertEquals("8/4", cursor.getString(0))
            }
            db.execSQL("INSERT INTO dance_lookup (mbid, checkedAt) VALUES ('m-2', 6)")
            db.query("SELECT meter, subMeter FROM dance_lookup WHERE mbid = 'm-2'").use { cursor ->
                cursor.moveToFirst()
                assertNull(cursor.getString(0))
                assertNull(cursor.getString(1))
            }
        }
    }
}
