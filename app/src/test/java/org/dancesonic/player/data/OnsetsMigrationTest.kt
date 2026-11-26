package org.dancesonic.player.data

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import org.dancesonic.player.data.db.MIGRATION_12_13
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class OnsetsMigrationTest {
    @Test
    fun `migration 12 to 13 adds the onsets table and keeps the waveforms that exist`() {
        val config =
            SupportSQLiteOpenHelper.Configuration
                .builder(ApplicationProvider.getApplicationContext())
                .name(null)
                .callback(
                    object : SupportSQLiteOpenHelper.Callback(12) {
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
            MIGRATION_12_13.migrate(db)

            db.execSQL(
                "INSERT INTO onsets (songId, timesMs, strengths, fetchedAt) VALUES ('s-1', '10,250', '200,90', 7)",
            )
            db.query("SELECT timesMs, strengths, fetchedAt FROM onsets WHERE songId = 's-1'").use { cursor ->
                assertEquals(true, cursor.moveToFirst())
                assertEquals("10,250", cursor.getString(0))
                assertEquals("200,90", cursor.getString(1))
                assertEquals(7L, cursor.getLong(2))
            }
            db.query("SELECT peaks FROM waveform WHERE songId = 's-1'").use { cursor ->
                assertEquals(true, cursor.moveToFirst())
                assertEquals("1,2", cursor.getString(0))
            }
        }
    }
}
