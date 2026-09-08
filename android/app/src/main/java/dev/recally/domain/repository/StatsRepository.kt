package dev.recally.domain.repository

import dev.recally.domain.model.Stats

/**
 * `GET /stats`. Remote-only — only due cards are cached for offline use
 * (docs/android.md, "Offline-first sync"), so a failed fetch is a [Result]
 * failure case and the UI keeps showing whatever it last had.
 */
interface StatsRepository {
    suspend fun stats(): Result<Stats>
}
