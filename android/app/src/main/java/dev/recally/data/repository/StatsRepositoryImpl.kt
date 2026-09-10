package dev.recally.data.repository

import dev.recally.data.remote.RecallyApi
import dev.recally.data.remote.apiCall
import dev.recally.di.IoDispatcher
import dev.recally.domain.model.Stats
import dev.recally.domain.repository.Result
import dev.recally.domain.repository.StatsRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException

/**
 * Remote-only: only due cards are cached for offline use
 * (docs/android.md, "Offline-first sync"). Never throws — failures surface
 * as a [Result] failure case (cancellation excepted).
 */
class StatsRepositoryImpl
    @Inject
    constructor(
        private val api: RecallyApi,
        @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) : StatsRepository {
        override suspend fun stats(): Result<Stats> =
            withContext(ioDispatcher) {
                try {
                    when (val result = apiCall { api.stats() }) {
                        is Result.Success -> Result.Success(result.data.toDomain())
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
    }
