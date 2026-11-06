package org.dancesonic.player.data

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import org.dancesonic.player.data.db.MIGRATION_6_7
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ShoesMigrationTest {
    @Test
    fun `migration 6 to 7 adds the shoes tables and keeps existing tracks and playlists`() {
        val config =
            SupportSQLiteOpenHelper.Configuration.builder(ApplicationProvider.getApplicationContext())
                .name(null)
                .callback(
                    object : SupportSQLiteOpenHelper.Callback(1) {
                        override fun onCreate(db: SupportSQLiteDatabase) = Unit

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
            db.execSQL("CREATE TABLE `dance_lookup` (`mbid` TEXT NOT NULL, `checkedAt` INTEGER NOT NULL)")
            db.execSQL("INSERT INTO dance_lookup (mbid, checkedAt) VALUES ('rec-1', 5)")

            MIGRATION_6_7.migrate(db)

            db.execSQL(
                "INSERT INTO shoes_rating (mbid, myShoes, median, raters, checkedAt) " +
                    "VALUES ('rec-1', 4, 3.5, 2, 9)",
            )
            db.execSQL(
                "INSERT INTO shoes_edit (mbid, value, queuedAt, lastError) " +
                    "VALUES ('rec-1', NULL, 10, 'offline')",
            )
            db.query("SELECT myShoes, median FROM shoes_rating WHERE mbid = 'rec-1'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(4, cursor.getInt(0))
                assertEquals(3.5, cursor.getDouble(1), 0.0)
            }
            db.query("SELECT value, lastError FROM shoes_edit WHERE mbid = 'rec-1'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertTrue(cursor.isNull(0))
                assertEquals("offline", cursor.getString(1))
            }
            db.query("SELECT checkedAt FROM dance_lookup WHERE mbid = 'rec-1'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(5L, cursor.getLong(0))
            }
        }
    }
}
