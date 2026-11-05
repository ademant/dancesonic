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

/** Stores the public tempo and its report count with each lookup. Existing lookups get no tempo until refreshed. */
val MIGRATION_2_3 =
    object : Migration(FROM_VERSION, TO_VERSION) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `dance_lookup` ADD COLUMN `bpm` INTEGER")
            db.execSQL("ALTER TABLE `dance_lookup` ADD COLUMN `raters` INTEGER")
        }
    }

private const val FROM_VERSION = 2
private const val TO_VERSION = 3
