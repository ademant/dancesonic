package org.dancesonic.player.data

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import org.dancesonic.player.data.db.MIGRATION_14_15
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ScrobbleQueueMigrationTest {
    @Test
    fun `migration 14 to 15 adds the scrobble queue, numbered in play order, and keeps existing data`() {
        val config =
            SupportSQLiteOpenHelper.Configuration.builder(ApplicationProvider.getApplicationContext())
                .name(null)
                .callback(
                    object : SupportSQLiteOpenHelper.Callback(14) {
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            db.execSQL(
                                "CREATE TABLE `waveform` (`songId` TEXT NOT NULL, `peaks` TEXT NOT NULL, " +
                                    "`source` TEXT NOT NULL, `computedAt` INTEGER NOT NULL, PRIMARY KEY(`songId`))",
                            )
                            db.execSQL(
                                "INSERT INTO waveform (songId, peaks, source, computedAt) " +
                                    "VALUES ('s-1', '1,2', 'server', 5)",
                            )
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
            MIGRATION_14_15.migrate(db)

            db.execSQL(
                "INSERT INTO scrobble_queue (serverUrl, songId, playedAt) VALUES ('https://a/', 's-1', 10)",
            )
            db.execSQL(
                "INSERT INTO scrobble_queue (serverUrl, songId, playedAt) VALUES ('https://a/', 's-2', 20)",
            )
            db.query("SELECT id, songId FROM scrobble_queue ORDER BY id").use { cursor ->
                assertEquals(true, cursor.moveToFirst())
                assertEquals(1L, cursor.getLong(0))
                assertEquals("s-1", cursor.getString(1))
                assertEquals(true, cursor.moveToNext())
                assertEquals(2L, cursor.getLong(0))
            }
            db.query("SELECT peaks FROM waveform WHERE songId = 's-1'").use { cursor ->
                assertEquals(true, cursor.moveToFirst())
                assertEquals("1,2", cursor.getString(0))
            }
        }
    }
}
