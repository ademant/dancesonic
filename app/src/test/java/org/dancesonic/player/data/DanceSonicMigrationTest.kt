package org.dancesonic.player.data

import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import org.dancesonic.player.data.db.MIGRATION_1_2
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
}
