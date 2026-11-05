package org.dancesonic.player.data

import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import org.dancesonic.player.data.db.MIGRATION_1_2
import org.dancesonic.player.data.db.MIGRATION_2_3
import org.dancesonic.player.data.db.MIGRATION_3_4
import org.dancesonic.player.data.db.MIGRATION_4_5
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DanceSonicMigrationTest {
    @Test
    fun `migration 1 to 2 creates the dance lookup table and keeps its rows usable`() {
        val config =
            SupportSQLiteOpenHelper.Configuration.builder(ApplicationProvider.getApplicationContext())
                .name(null)
                .callback(
                    object : SupportSQLiteOpenHelper.Callback(1) {
                        override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) = Unit

                        override fun onUpgrade(
                            db: androidx.sqlite.db.SupportSQLiteDatabase,
                            oldVersion: Int,
                            newVersion: Int,
                        ) = Unit
                    },
                ).build()
        val helper = FrameworkSQLiteOpenHelperFactory().create(config)
        helper.use {
            val db = it.writableDatabase
            MIGRATION_1_2.migrate(db)

            db.execSQL("INSERT INTO dance_lookup (mbid, checkedAt) VALUES ('rec-1', 5)")
            db.query("SELECT checkedAt FROM dance_lookup WHERE mbid = 'rec-1'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(5L, cursor.getLong(0))
            }
        }
    }

    @Test
    fun `migration 2 to 3 adds tempo columns and keeps existing lookups`() {
        val config =
            SupportSQLiteOpenHelper.Configuration.builder(ApplicationProvider.getApplicationContext())
                .name(null)
                .callback(
                    object : SupportSQLiteOpenHelper.Callback(1) {
                        override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) = Unit

                        override fun onUpgrade(
                            db: androidx.sqlite.db.SupportSQLiteDatabase,
                            oldVersion: Int,
                            newVersion: Int,
                        ) = Unit
                    },
                ).build()
        val helper = FrameworkSQLiteOpenHelperFactory().create(config)
        helper.use {
            val db = it.writableDatabase
            MIGRATION_1_2.migrate(db)
            db.execSQL("INSERT INTO dance_lookup (mbid, checkedAt) VALUES ('rec-1', 5)")

            MIGRATION_2_3.migrate(db)

            db.query("SELECT checkedAt, bpm FROM dance_lookup WHERE mbid = 'rec-1'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(5L, cursor.getLong(0))
                assertTrue(cursor.isNull(1))
            }
            db.execSQL("UPDATE dance_lookup SET bpm = 118, raters = 4 WHERE mbid = 'rec-1'")
            db.query("SELECT bpm FROM dance_lookup WHERE mbid = 'rec-1'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(118, cursor.getInt(0))
            }
        }
    }

    @Test
    fun `migration 3 to 4 adds the server tempo to tracks`() {
        val config =
            SupportSQLiteOpenHelper.Configuration.builder(ApplicationProvider.getApplicationContext())
                .name(null)
                .callback(
                    object : SupportSQLiteOpenHelper.Callback(1) {
                        override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) = Unit

                        override fun onUpgrade(
                            db: androidx.sqlite.db.SupportSQLiteDatabase,
                            oldVersion: Int,
                            newVersion: Int,
                        ) = Unit
                    },
                ).build()
        val helper = FrameworkSQLiteOpenHelperFactory().create(config)
        helper.use {
            val db = it.writableDatabase
            db.execSQL("CREATE TABLE `track` (`id` TEXT NOT NULL, PRIMARY KEY(`id`))")
            db.execSQL("INSERT INTO track (id) VALUES ('s-1')")

            MIGRATION_3_4.migrate(db)

            db.execSQL("UPDATE track SET bpm = 118 WHERE id = 's-1'")
            db.query("SELECT bpm FROM track WHERE id = 's-1'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(118, cursor.getInt(0))
            }
        }
    }

    @Test
    fun `migration 4 to 5 adds the ReplayGain columns to tracks`() {
        val config =
            SupportSQLiteOpenHelper.Configuration.builder(ApplicationProvider.getApplicationContext())
                .name(null)
                .callback(
                    object : SupportSQLiteOpenHelper.Callback(1) {
                        override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) = Unit

                        override fun onUpgrade(
                            db: androidx.sqlite.db.SupportSQLiteDatabase,
                            oldVersion: Int,
                            newVersion: Int,
                        ) = Unit
                    },
                ).build()
        val helper = FrameworkSQLiteOpenHelperFactory().create(config)
        helper.use {
            val db = it.writableDatabase
            db.execSQL("CREATE TABLE `track` (`id` TEXT NOT NULL, PRIMARY KEY(`id`))")
            db.execSQL("INSERT INTO track (id) VALUES ('s-1')")

            MIGRATION_4_5.migrate(db)

            db.execSQL("UPDATE track SET trackGain = -5.5, trackPeak = 0.97 WHERE id = 's-1'")
            db.query("SELECT trackGain, trackPeak FROM track WHERE id = 's-1'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(-5.5, cursor.getDouble(0), 1e-9)
                assertEquals(0.97, cursor.getDouble(1), 1e-9)
            }
        }
    }
}
