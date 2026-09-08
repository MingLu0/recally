package dev.recally.data.sync

/** Outcome of one outbox flush attempt; the WorkManager worker maps it to retry/success. */
sealed interface FlushResult {
    /**
     * Nothing left to retry: [acked] rows were acknowledged (including
     * duplicates) and [dropped] rows were discarded (4xx items or a poison
     * batch the server could not parse).
     */
    data class Completed(
        val acked: Int,
        val dropped: Int,
    ) : FlushResult

    /** [remaining] rows are still queued (5xx items or no server); retry later. */
    data class Retry(
        val remaining: Int,
    ) : FlushResult
}
