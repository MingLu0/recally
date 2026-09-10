package dev.recally.data.repository

import androidx.room.withTransaction
import dev.recally.data.local.RecallyDatabase
import dev.recally.data.remote.EditCardRequest
import dev.recally.data.remote.RecallyApi
import dev.recally.data.remote.apiCall
import dev.recally.di.IoDispatcher
import dev.recally.domain.model.DueSummary
import dev.recally.domain.repository.CardRepository
import dev.recally.domain.repository.Result
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.time.Instant
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException

/**
 * Room is the source of truth for due cards (docs/android.md,
 * "Repositories own the data layer"): a successful fetch replaces the cache
 * wholesale, and a failed fetch falls back to it, so a review session works
 * fully offline. All failures surface as a [Result] failure case — nothing
 * throws (cancellation excepted).
 */
class CardRepositoryImpl
    @Inject
    constructor(
        private val api: RecallyApi,
        private val database: RecallyDatabase,
        @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) : CardRepository {
        override suspend fun dueCards(forceRefresh: Boolean): Result<DueSummary> =
            withContext(ioDispatcher) {
                val cached = readCache()
                if (!forceRefresh && cached != null) {
                    Result.Success(cached)
                } else {
                    when (val fresh = fetchAndCache()) {
                        is Result.Success -> fresh
                        // A 401 is never answered from cache: the server was
                        // reached and rejected the key, so the UI must show the
                        // "check settings" banner, not stale data with an
                        // offline bar (docs/android.md, "Connecting to the
                        // backend").
                        is Result.Unauthorized -> Result.Unauthorized
                        is Result.HttpError -> cached?.let { Result.Success(it, servedFromCache = true) } ?: fresh
                        is Result.NetworkError -> cached?.let { Result.Success(it, servedFromCache = true) } ?: fresh
                        // Same shape as an HTTP failure: the refresh did not
                        // land, so stale cards beat no cards and a review
                        // session still works. With no cache the fault
                        // surfaces as itself, never relabelled "offline"
                        // (issue #188).
                        is Result.UnexpectedError -> cached?.let { Result.Success(it, servedFromCache = true) } ?: fresh
                    }
                }
            }

        override suspend fun refreshDueCards(): Result<DueSummary> = dueCards(forceRefresh = true)

        // ADR-008 post-approval controls. All three are remote-only writes —
        // none is queued offline (docs/android.md, "Offline-first sync") —
        // and none touches FSRS state or the Room due-card cache: the server
        // stays the scheduling authority and the next refresh of
        // GET /reviews/due reflects whatever it decided.
        override suspend fun editCard(
            cardId: Long,
            front: String?,
            back: String?,
            tags: List<String>?,
        ): Result<Unit> =
            withContext(ioDispatcher) {
                try {
                    when (
                        val result =
                            apiCall { api.editCard(cardId, EditCardRequest(front = front, back = back, tags = tags)) }
                    ) {
                        is Result.Success -> Result.Success(Unit)
                        is Result.Unauthorized -> Result.Unauthorized
                        is Result.HttpError -> result
                        is Result.NetworkError -> result
                        is Result.UnexpectedError -> result
                    }
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (exception: Exception) {
                    Result.UnexpectedError(exception)
                }
            }

        override suspend fun suspendCard(cardId: Long): Result<Instant?> = suspendedUntilCall(cardId) { api.suspendCard(it) }

        override suspend fun unsuspendCard(cardId: Long): Result<Instant?> = suspendedUntilCall(cardId) { api.unsuspendCard(it) }

        private suspend fun suspendedUntilCall(
            cardId: Long,
            call: suspend (Long) -> dev.recally.data.remote.SuspendedUntilResponse,
        ): Result<Instant?> =
            withContext(ioDispatcher) {
                try {
                    when (val result = apiCall { call(cardId) }) {
                        is Result.Success -> Result.Success(result.data.suspendedUntil?.let(Instant::parse))
                        is Result.Unauthorized -> Result.Unauthorized
                        is Result.HttpError -> result
                        is Result.NetworkError -> result
                        is Result.UnexpectedError -> result
                    }
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (exception: Exception) {
                    Result.UnexpectedError(exception)
                }
            }

        private suspend fun fetchAndCache(): Result<DueSummary> =
            try {
                val result = apiCall { api.dueCards().toDomain() }
                if (result is Result.Success) {
                    val summary = result.data
                    database.withTransaction {
                        database.dueCardDao().deleteAll()
                        database.dueCardDao().upsertAll(summary.cards.map { it.toEntity() })
                        database.dueSummaryMetaDao().upsert(summary.toMetaEntity())
                    }
                }
                result
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (exception: Exception) {
                // apiCall already maps HTTP and network failures; this catches
                // anything left (e.g. a malformed body) so the repository still
                // never throws.
                Result.UnexpectedError(exception)
            }

        /** Null when nothing has ever been cached. */
        private suspend fun readCache(): DueSummary? {
            val meta = database.dueSummaryMetaDao().get() ?: return null
            val cards = database.dueCardDao().getAll().map { it.toDomain() }
            return meta.toDomain(cards)
        }
    }
