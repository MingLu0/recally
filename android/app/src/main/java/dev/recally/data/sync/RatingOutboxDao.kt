package dev.recally.data.sync

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface RatingOutboxDao {
    @Insert
    suspend fun insert(rating: RatingOutboxEntity): Long

    /** Live queue depth — drives the review session's "N ratings queued" bar. */
    @Query("SELECT COUNT(*) FROM rating_outbox")
    fun observeQueuedCount(): Flow<Int>

    /** Queue order: insertion order, which is also the batch request order. */
    @Query("SELECT * FROM rating_outbox ORDER BY id ASC")
    suspend fun getAll(): List<RatingOutboxEntity>

    @Query("DELETE FROM rating_outbox WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)
}
