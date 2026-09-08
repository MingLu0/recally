package dev.recally.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Single-row companion to the due-card cache holding the parts of
 * GET /reviews/due that are not cards: the counts and the FSRS
 * `learning_steps_minutes` the review session re-queues by. Written in the
 * same transaction as the cards so the cache is never half-fresh.
 */
@Entity(tableName = "due_summary_meta")
data class DueSummaryMetaEntity(
    @PrimaryKey val id: Int = SINGLETON_ID,
    val dueCount: Int,
    val newCount: Int,
    val learningStepsJson: String,
) {
    companion object {
        const val SINGLETON_ID = 1
    }
}
