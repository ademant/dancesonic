package org.dancesonic.player.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

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
        WaveformEntity::class,
        OnsetsEntity::class,
        ScrobbleQueueEntity::class,
    ],
    version = 16,
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

    abstract fun waveformDao(): WaveformDao

    abstract fun scrobbleQueueDao(): ScrobbleQueueDao

    companion object {
        fun create(context: Context): DanceSonicDatabase =
            Room
                .databaseBuilder(context, DanceSonicDatabase::class.java, "dancesonic.db")
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
                    MIGRATION_10_11,
                    MIGRATION_11_12,
                    MIGRATION_12_13,
                    MIGRATION_13_14,
                    MIGRATION_14_15,
                    MIGRATION_15_16,
                ).setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
                // Room turns foreign keys on itself, because the schema declares some (#127).
                // No fallbackToDestructiveMigration: playlists and cached metadata must survive upgrades.
                .build()
    }
}
