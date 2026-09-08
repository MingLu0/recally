package dev.recally.data.repository

import dev.recally.data.remote.RecallyApi
import dev.recally.di.IoDispatcher
import dev.recally.domain.model.Deck
import dev.recally.domain.repository.DeckRepository
import dev.recally.domain.repository.Result
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import retrofit2.HttpException
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException

/**
 * Remote-only: only due cards are cached for offline use
 * (docs/android.md, "Offline-first sync"). Never throws — failures surface
 * as [Result.Failure] (cancellation excepted).
 */
class DeckRepositoryImpl
    @Inject
    constructor(
        private val api: RecallyApi,
        @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) : DeckRepository {
        override suspend fun decks(): Result<List<Deck>> =
            withContext(ioDispatcher) {
                try {
                    Result.Success(api.getDecks().decks.map { it.toDomain() })
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (exception: Exception) {
                    Result.Failure(
                        message = exception.message ?: "failed to fetch decks",
                        httpStatus = (exception as? HttpException)?.code(),
                        cause = exception,
                    )
                }
            }
    }
