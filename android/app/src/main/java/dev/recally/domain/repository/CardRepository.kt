package dev.recally.domain.repository

import dev.recally.domain.model.DueSummary
import java.time.Instant

/**
 * Due cards for review. Room is the source of truth — a review session works
 * fully offline — and the implementation decides when to refresh from the
 * API; callers never know which side answered (docs/android.md,
 * "Repositories own the data layer").
 */
interface CardRepository {
    /**
     * The cached due summary when present, refreshing from the API when the
     * cache is empty or [forceRefresh] is set. A failed refresh falls back to
     * the cache marked [Result.Success.servedFromCache] so the UI can show the
     * offline bar; only an empty cache plus a failed fetch is a `Result`
     * failure case. A 401 never falls back — the server was reached and
     * rejected the key, so it always surfaces as [Result.Unauthorized] for
     * the banner.
     */
    suspend fun dueCards(forceRefresh: Boolean = false): Result<DueSummary>

    /** Force a refresh; a successful fetch replaces the cache wholesale. */
    suspend fun refreshDueCards(): Result<DueSummary>

    /**
     * `PATCH /cards/{id}` (ADR-008): updates text/tags on an approved card
     * and leaves all FSRS state untouched. Requires connectivity — never
     * queued (docs/android.md, "Offline-first sync").
     */
    suspend fun editCard(
        cardId: Long,
        front: String? = null,
        back: String? = null,
        tags: List<String>? = null,
    ): Result<Unit>

    /**
     * `POST /cards/{id}/suspend` (ADR-008): takes the card out of rotation
     * indefinitely. Returns the server's `suspended_until` (a far-future
     * sentinel). Requires connectivity — never queued.
     */
    suspend fun suspendCard(cardId: Long): Result<Instant?>

    /**
     * `POST /cards/{id}/unsuspend` (ADR-008): clears `suspended_until`; the
     * server recomputes nothing and neither does the client. Returns null on
     * success — the card is simply visible to `GET /reviews/due` again.
     * Requires connectivity — never queued.
     */
    suspend fun unsuspendCard(cardId: Long): Result<Instant?>
}
