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

/** Adds the server's tempo to each track. Existing tracks get it on the next full listing. */
val MIGRATION_3_4 =
    object : Migration(FROM_3, TO_4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `track` ADD COLUMN `bpm` INTEGER")
        }
    }

private const val FROM_VERSION = 2
private const val TO_VERSION = 3
private const val FROM_3 = 3
private const val TO_4 = 4
private const val FROM_4 = 4
private const val TO_5 = 5

/** Adds the server's ReplayGain track values to each track. Existing tracks get them on the next full listing. */
val MIGRATION_4_5 =
    object : Migration(FROM_4, TO_5) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `track` ADD COLUMN `trackGain` REAL")
            db.execSQL("ALTER TABLE `track` ADD COLUMN `trackPeak` REAL")
        }
    }
