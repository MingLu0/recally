package dev.recally.data.sync

import dev.recally.di.IoDispatcher
import dev.recally.domain.model.ReviewRating
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * The single write path into the rating outbox (docs/android.md,
 * "Offline-first sync"). An interface so the review session's tests fake it
 * without a Room database — the same seam as [FlushScheduler].
 */
interface RatingOutbox {
    /** Persist [rating], then schedule the flush — in that order. */
    suspend fun record(rating: ReviewRating)

    /**
     * Persisted queue depth. The review session's "N ratings queued" bar
     * reads this and nothing else, so the figure cannot disagree with what is
     * actually stored.
     */
    fun queuedCount(): Flow<Int>
}

/**
 * Room-backed [RatingOutbox]. **Persist first, schedule second**: the row
 * exists in Room before any network attempt, so process death cannot lose a
 * rating. Ratings are the only queued write — approve, reject, edit, bury,
 * suspend and unsuspend require connectivity and never reach this class.
 */
class RoomRatingOutbox
    @Inject
    constructor(
        private val dao: RatingOutboxDao,
        private val flushScheduler: FlushScheduler,
        @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) : RatingOutbox {
        override suspend fun record(rating: ReviewRating) {
            withContext(ioDispatcher) {
                dao.insert(RatingOutboxEntity.fromDomain(rating))
            }
            flushScheduler.enqueueAfterRating()
        }

        override fun queuedCount(): Flow<Int> = dao.observeQueuedCount()
    }
