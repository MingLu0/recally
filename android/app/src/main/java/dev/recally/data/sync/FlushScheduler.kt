package dev.recally.data.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/**
 * Schedules the outbox flush (docs/android.md, "Offline-first sync"):
 * expedited on app start and enqueued after each rating. An interface so the
 * outbox and its tests do not depend on a running WorkManager.
 */
interface FlushScheduler {
    fun enqueueAfterRating()
}

class WorkManagerFlushScheduler
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) : FlushScheduler {
        override fun enqueueAfterRating() {
            RatingOutboxWork.enqueue(context, expedited = false)
        }
    }

/** Enqueue helpers for the flush worker; unique work so taps do not pile up. */
object RatingOutboxWork {
    private const val UNIQUE_WORK_NAME = "rating_outbox_flush"

    fun enqueue(
        context: Context,
        expedited: Boolean,
    ) {
        val request =
            OneTimeWorkRequestBuilder<RatingOutboxFlushWorker>()
                .setConstraints(
                    Constraints
                        .Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                ).apply {
                    if (expedited) setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                }.build()
        // REPLACE: a fresh tap supersedes a queued flush — the worker reads
        // the whole outbox at run time, so the newest enqueue flushes everything.
        WorkManager
            .getInstance(context)
            .enqueueUniqueWork(UNIQUE_WORK_NAME, ExistingWorkPolicy.REPLACE, request)
    }
}
