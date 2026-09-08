package dev.recally.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Cached due card (docs/android.md, "Offline-first sync"). Lists are stored
 * as JSON strings ([tagsJson]) so the entity stays a flat row; mapping to the
 * domain model lives in data/repository.
 *
 * [due] is the ISO-8601 timestamp as the server sent it (null while a card
 * has no due date); the domain model parses it to an Instant.
 */
@Entity(tableName = "due_cards")
data class DueCardEntity(
    @PrimaryKey val id: Long,
    val unitId: Long,
    val type: String,
    val front: String,
    val back: String,
    val bookId: Long,
    val book: String,
    val chapter: String,
    val tagsJson: String,
    val state: String,
    val step: Int?,
    val due: String?,
)
