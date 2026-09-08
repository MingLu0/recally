package dev.recally.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import dev.recally.data.sync.RatingOutboxDao
import dev.recally.data.sync.RatingOutboxEntity

/**
 * The app's Room database.
 *
 * Version 2 adds step 4i's `rating_outbox` (docs/android.md, "Offline-first
 * sync").
 */
@Database(
    entities = [DueCardEntity::class, DueSummaryMetaEntity::class, RatingOutboxEntity::class],
    version = 2,
    exportSchema = false,
)
abstract class RecallyDatabase : RoomDatabase() {
    abstract fun dueCardDao(): DueCardDao

    abstract fun dueSummaryMetaDao(): DueSummaryMetaDao

    abstract fun ratingOutboxDao(): RatingOutboxDao
}
