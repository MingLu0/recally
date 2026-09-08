package dev.recally.data.sync

import dev.recally.di.IoDispatcher
import dev.recally.domain.model.ReviewRating
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * The single write path into the rating outbox (docs/android.md,
 * "Offline-first sync"). **Persist first, schedule second**: the row exists
 * in Room before any network attempt, so process death cannot lose a rating.
 * Ratings are the only queued write — approve, reject, edit, bury, suspend
 * and unsuspend require connectivity and never reach this class.
 */
class RatingOutbox
    @Inject
    constructor(
        private val dao: RatingOutboxDao,
        private val flushScheduler: FlushScheduler,
        @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) {
        suspend fun record(rating: ReviewRating) {
            withContext(ioDispatcher) {
                dao.insert(RatingOutboxEntity.fromDomain(rating))
            }
            flushScheduler.enqueueAfterRating()
        }
    }
