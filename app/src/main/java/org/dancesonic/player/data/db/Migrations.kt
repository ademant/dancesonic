package org.dancesonic.player.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Adds the DanceBrainz lookup record. Existing playlists and cached metadata are kept. */
val MIGRATION_1_2 =
    object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `dance_lookup` " +
                    "(`mbid` TEXT NOT NULL, `checkedAt` INTEGER NOT NULL, PRIMARY KEY(`mbid`))",
            )
        }
    }
