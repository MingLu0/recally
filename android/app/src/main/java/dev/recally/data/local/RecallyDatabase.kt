package dev.recally.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * The app's Room database.
 *
 * The rating outbox table is step 4i's — when it lands it adds its entity
 * here (bumping the version), rather than this step pre-declaring it.
 */
@Database(
    entities = [DueCardEntity::class, DueSummaryMetaEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class RecallyDatabase : RoomDatabase() {
    abstract fun dueCardDao(): DueCardDao

    abstract fun dueSummaryMetaDao(): DueSummaryMetaDao
}
