package dev.recally.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface DueCardDao {
    @Query("SELECT * FROM due_cards")
    suspend fun getAll(): List<DueCardEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(cards: List<DueCardEntity>)

    @Query("DELETE FROM due_cards")
    suspend fun deleteAll()
}
