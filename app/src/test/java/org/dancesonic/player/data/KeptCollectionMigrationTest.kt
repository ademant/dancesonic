package org.dancesonic.player.data

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import org.dancesonic.player.data.db.MIGRATION_8_9
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class KeptCollectionMigrationTest {
    @Test
    fun `migration 8 to 9 adds the kept collections table with one row per album or playlist`() {
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
            MIGRATION_8_9.migrate(db)

            db.execSQL("INSERT INTO kept_collection (kind, refId, keptAt) VALUES ('album', '7', 10)")
            db.execSQL("INSERT INTO kept_collection (kind, refId, keptAt) VALUES ('playlist', '7', 11)")
            // The same album twice is one choice, not two.
            assertThrows(android.database.sqlite.SQLiteConstraintException::class.java) {
                db.execSQL("INSERT INTO kept_collection (kind, refId, keptAt) VALUES ('album', '7', 12)")
            }

            db.query("SELECT COUNT(*) FROM kept_collection").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(2, cursor.getInt(0))
            }
        }
    }
}
