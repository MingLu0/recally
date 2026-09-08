package dev.recally.data.repository

import dev.recally.data.remote.RecallyApi
import dev.recally.data.remote.apiCall
import dev.recally.di.IoDispatcher
import dev.recally.domain.model.Deck
import dev.recally.domain.repository.DeckRepository
import dev.recally.domain.repository.Result
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.IOException
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException

/**
 * Remote-only: only due cards are cached for offline use
 * (docs/android.md, "Offline-first sync"). Never throws — failures surface
 * as a [Result] failure case (cancellation excepted).
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
                    when (val result = apiCall { api.decks() }) {
                        is Result.Success -> Result.Success(result.data.decks.map { it.toDomain() })
                        is Result.Unauthorized -> Result.Unauthorized
                        is Result.HttpError -> result
                        is Result.NetworkError -> result
                    }
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (exception: Exception) {
                    Result.NetworkError(IOException("failed to load decks", exception))
                }
            }
    }
