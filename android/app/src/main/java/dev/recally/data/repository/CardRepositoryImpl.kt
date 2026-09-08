package dev.recally.data.repository

import androidx.room.withTransaction
import dev.recally.data.local.RecallyDatabase
import dev.recally.data.remote.RecallyApi
import dev.recally.data.remote.apiCall
import dev.recally.di.IoDispatcher
import dev.recally.domain.model.DueSummary
import dev.recally.domain.repository.CardRepository
import dev.recally.domain.repository.Result
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.IOException
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
                    }
                }
            }

        override suspend fun refreshDueCards(): Result<DueSummary> = dueCards(forceRefresh = true)

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
                Result.NetworkError(IOException("failed to load due cards", exception))
            }

        /** Null when nothing has ever been cached. */
        private suspend fun readCache(): DueSummary? {
            val meta = database.dueSummaryMetaDao().get() ?: return null
            val cards = database.dueCardDao().getAll().map { it.toDomain() }
            return meta.toDomain(cards)
        }
    }
