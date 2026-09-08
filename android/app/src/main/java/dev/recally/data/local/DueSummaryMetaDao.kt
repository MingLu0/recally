package dev.recally.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface DueSummaryMetaDao {
    @Query("SELECT * FROM due_summary_meta WHERE id = 1")
    suspend fun get(): DueSummaryMetaEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(meta: DueSummaryMetaEntity)
}
