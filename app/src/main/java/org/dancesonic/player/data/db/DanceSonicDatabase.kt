package org.dancesonic.player.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        ArtistEntity::class,
        AlbumEntity::class,
        TrackEntity::class,
        DanceMetadataEntity::class,
        DanceLookupEntity::class,
        PlaylistEntity::class,
        PlaylistItemEntity::class,
        ShoesRatingEntity::class,
        EditEntity::class,
        KeptCollectionEntity::class,
        ChapterEntity::class,
    ],
    version = 10,
    exportSchema = true,
)
abstract class DanceSonicDatabase : RoomDatabase() {
    abstract fun artistDao(): ArtistDao

    abstract fun albumDao(): AlbumDao

    abstract fun trackDao(): TrackDao

    abstract fun danceMetadataDao(): DanceMetadataDao

    abstract fun danceLookupDao(): DanceLookupDao

    abstract fun playlistDao(): PlaylistDao

    abstract fun playlistItemDao(): PlaylistItemDao

    abstract fun shoesRatingDao(): ShoesRatingDao

    abstract fun editQueueDao(): EditQueueDao

    abstract fun keptCollectionDao(): KeptCollectionDao

    abstract fun chapterDao(): ChapterDao

    companion object {
        fun create(context: Context): DanceSonicDatabase =
            Room.databaseBuilder(context, DanceSonicDatabase::class.java, "dancesonic.db")
                .addMigrations(
                    MIGRATION_1_2,
                    MIGRATION_2_3,
                    MIGRATION_3_4,
                    MIGRATION_4_5,
                    MIGRATION_5_6,
                    MIGRATION_6_7,
                    MIGRATION_7_8,
                    MIGRATION_8_9,
                    MIGRATION_9_10,
                )
                .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
                .addCallback(
                    object : RoomDatabase.Callback() {
                        override fun onOpen(db: SupportSQLiteDatabase) {
                            db.execSQL("PRAGMA foreign_keys = ON")
                        }
                    },
                )
                // No fallbackToDestructiveMigration: playlists and cached metadata must survive upgrades.
                .build()
    }
}
