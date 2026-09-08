package dev.recally.data.repository

import androidx.room.withTransaction
import dev.recally.data.local.RecallyDatabase
import dev.recally.data.remote.RecallyApi
import dev.recally.di.IoDispatcher
import dev.recally.domain.model.DueSummary
import dev.recally.domain.repository.CardRepository
import dev.recally.domain.repository.Result
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import retrofit2.HttpException
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException

/**
 * Room is the source of truth for due cards (docs/android.md,
 * "Repositories own the data layer"): a successful fetch replaces the cache
 * wholesale, and a failed fetch falls back to it, so a review session works
 * fully offline. All failures surface as [Result.Failure] — nothing throws
 * (cancellation excepted).
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
                        is Result.Failure -> cached?.let { Result.Success(it) } ?: fresh
                    }
                }
            }

        override suspend fun refreshDueCards(): Result<DueSummary> = dueCards(forceRefresh = true)

        private suspend fun fetchAndCache(): Result<DueSummary> =
            try {
                val summary = api.getDueCards().toDomain()
                database.withTransaction {
                    database.dueCardDao().deleteAll()
                    database.dueCardDao().upsertAll(summary.cards.map { it.toEntity() })
                    database.dueSummaryMetaDao().upsert(summary.toMetaEntity())
                }
                Result.Success(summary)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (exception: Exception) {
                Result.Failure(
                    message = exception.message ?: "failed to fetch due cards",
                    httpStatus = (exception as? HttpException)?.code(),
                    cause = exception,
                )
            }

        /** Null when nothing has ever been cached. */
        private suspend fun readCache(): DueSummary? {
            val meta = database.dueSummaryMetaDao().get() ?: return null
            val cards = database.dueCardDao().getAll().map { it.toDomain() }
            return meta.toDomain(cards)
        }
    }
