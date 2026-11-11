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

/**
 * Adds the file size of each track. The audio cache needs it to recognise a complete file. Existing tracks get
 * none until a full rebuild.
 */
val MIGRATION_5_6 =
    object : Migration(FROM_5, TO_6) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `track` ADD COLUMN `sizeBytes` INTEGER")
        }
    }

private const val FROM_5 = 5
private const val TO_6 = 6

/**
 * Adds the shoes ratings read from DanceBrainz and the queue of shoes changes not yet sent. Nothing existing
 * changes.
 */
val MIGRATION_6_7 =
    object : Migration(FROM_6, TO_7) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `shoes_rating` " +
                    "(`mbid` TEXT NOT NULL, `myShoes` INTEGER, `median` REAL, `raters` INTEGER NOT NULL, " +
                    "`checkedAt` INTEGER NOT NULL, PRIMARY KEY(`mbid`))",
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `shoes_edit` " +
                    "(`mbid` TEXT NOT NULL, `value` INTEGER, `queuedAt` INTEGER NOT NULL, `lastError` TEXT, " +
                    "PRIMARY KEY(`mbid`))",
            )
        }
    }

private const val FROM_6 = 6
private const val TO_7 = 7

/**
 * Replaces the shoes-only queue with one queue for every DanceBrainz change, so shoes ratings and dance tags share
 * a queue. Queued shoes changes are carried over.
 */
val MIGRATION_7_8 =
    object : Migration(FROM_7, TO_8) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `edit_queue` " +
                    "(`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `kind` TEXT NOT NULL, `mbid` TEXT NOT NULL, " +
                    "`value` INTEGER, `danceName` TEXT, `path` TEXT, `qualifier` TEXT, `expectedDances` TEXT, " +
                    "`status` TEXT NOT NULL, `queuedAt` INTEGER NOT NULL, `lastError` TEXT)",
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_edit_queue_mbid` ON `edit_queue` (`mbid`)")
            db.execSQL(
                "INSERT INTO `edit_queue` (`kind`, `mbid`, `value`, `status`, `queuedAt`, `lastError`) " +
                    "SELECT 'shoes', `mbid`, `value`, 'queued', `queuedAt`, `lastError` FROM `shoes_edit`",
            )
            db.execSQL("DROP TABLE `shoes_edit`")
        }
    }

private const val FROM_7 = 7
private const val TO_8 = 8

/** Adds the albums and playlists the user keeps on the phone. Nothing existing changes. */
val MIGRATION_8_9 =
    object : Migration(FROM_8, TO_9) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `kept_collection` " +
                    "(`kind` TEXT NOT NULL, `refId` TEXT NOT NULL, `keptAt` INTEGER NOT NULL, " +
                    "PRIMARY KEY(`kind`, `refId`))",
            )
        }
    }

private const val FROM_8 = 8
private const val TO_9 = 9

/** Adds the chapter marks of a recording and the chapters version each lookup returns. Nothing existing changes. */
val MIGRATION_9_10 =
    object : Migration(FROM_9, TO_10) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `chapter` " +
                    "(`mbid` TEXT NOT NULL, `startMs` INTEGER NOT NULL, `endMs` INTEGER, `title` TEXT, " +
                    "`danceTagId` INTEGER, `danceName` TEXT, PRIMARY KEY(`mbid`, `startMs`))",
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_chapter_mbid` ON `chapter` (`mbid`)")
            db.execSQL("ALTER TABLE `dance_lookup` ADD COLUMN `chaptersVersion` TEXT")
        }
    }

/** Adds the waveform table: one row per song with its peaks. Existing rows are untouched. */
val MIGRATION_10_11 =
    object : Migration(FROM_10, TO_11) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `waveform` " +
                    "(`songId` TEXT NOT NULL, `peaks` TEXT NOT NULL, `source` TEXT NOT NULL, " +
                    "`computedAt` INTEGER NOT NULL, PRIMARY KEY(`songId`))",
            )
        }
    }

private const val FROM_9 = 9
private const val TO_10 = 10
private const val FROM_10 = 10
private const val TO_11 = 11
