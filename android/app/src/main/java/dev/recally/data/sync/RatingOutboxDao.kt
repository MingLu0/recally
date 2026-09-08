package dev.recally.data.sync

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface RatingOutboxDao {
    @Insert
    suspend fun insert(rating: RatingOutboxEntity): Long

    /** Queue order: insertion order, which is also the batch request order. */
    @Query("SELECT * FROM rating_outbox ORDER BY id ASC")
    suspend fun getAll(): List<RatingOutboxEntity>

    @Query("DELETE FROM rating_outbox WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)
}
