package org.dancesonic.player.data

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import org.dancesonic.player.data.db.MIGRATION_6_7
import org.dancesonic.player.data.db.MIGRATION_7_8
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class EditQueueMigrationTest {
    @Test
    fun `migration 7 to 8 carries queued shoes changes into the edit queue and drops the old table`() {
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
            MIGRATION_6_7.migrate(db)
            db.execSQL("INSERT INTO shoes_edit (mbid, value, queuedAt, lastError) VALUES ('rec-1', 3, 10, 'offline')")

            MIGRATION_7_8.migrate(db)

            db.query("SELECT kind, mbid, value, status, queuedAt, lastError FROM edit_queue").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("shoes", cursor.getString(0))
                assertEquals("rec-1", cursor.getString(1))
                assertEquals(3, cursor.getInt(2))
                assertEquals("queued", cursor.getString(3))
                assertEquals(10L, cursor.getLong(4))
                assertEquals("offline", cursor.getString(5))
                assertFalse(cursor.moveToNext())
            }
            db.query("SELECT COUNT(*) FROM sqlite_master WHERE name = 'shoes_edit'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(0, cursor.getInt(0))
            }
            db.execSQL(
                "INSERT INTO edit_queue (kind, mbid, status, queuedAt) VALUES ('dance_add', 'rec-2', 'queued', 11)",
            )
            db.query("SELECT id FROM edit_queue WHERE mbid = 'rec-2'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertTrue(cursor.getLong(0) > 0)
            }
        }
    }
}
