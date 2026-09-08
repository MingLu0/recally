package dev.recally.data.sync

import androidx.room.Entity
import androidx.room.PrimaryKey
import dev.recally.domain.model.ReviewRating

/**
 * A rating written to Room the moment the user taps it — not held in memory —
 * so process death mid-session cannot lose it (docs/android.md, "Offline-first
 * sync"). The row is deleted only once the server has acknowledged it (or been
 * told to drop it; see [RatingOutboxFlusher]).
 *
 * [ratedAt] is the client timestamp exactly as captured on tap, stored as an
 * ISO-8601 string so a retried flush sends a byte-identical payload; the
 * server replays the rating at this instant. [deviceId] is the id from
 * `POST /devices` and ends up on `review_logs.device_id`.
 */
@Entity(tableName = "rating_outbox")
data class RatingOutboxEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val cardId: Long,
    val rating: Int,
    val responseMs: Long,
    val ratedAt: String,
    val deviceId: Long?,
) {
    companion object {
        fun fromDomain(rating: ReviewRating): RatingOutboxEntity =
            RatingOutboxEntity(
                cardId = rating.cardId,
                rating = rating.rating,
                responseMs = rating.responseMs,
                ratedAt = rating.ratedAt.toString(),
                deviceId = rating.deviceId,
            )
    }
}
