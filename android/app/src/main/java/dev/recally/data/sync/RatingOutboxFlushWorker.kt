package dev.recally.data.sync

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * WorkManager flush of the rating outbox (docs/android.md, "Offline-first
 * sync"). Kept deliberately thin — the policy lives in [RatingOutboxFlusher]
 * so unit tests exercise it without a WorkManager runtime.
 */
@HiltWorker
class RatingOutboxFlushWorker
    @AssistedInject
    constructor(
        @Assisted context: Context,
        @Assisted params: WorkerParameters,
        private val flusher: RatingOutboxFlusher,
    ) : CoroutineWorker(context, params) {
        override suspend fun doWork(): Result =
            when (flusher.flush()) {
                is FlushResult.Completed -> Result.success()
                is FlushResult.Retry -> Result.retry()
            }
    }
