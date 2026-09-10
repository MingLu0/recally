package dev.recally.data.settings

import dev.recally.data.remote.RecallyApi
import dev.recally.data.remote.apiCall
import dev.recally.di.IoDispatcher
import dev.recally.domain.repository.ConnectionTester
import dev.recally.domain.repository.Result
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * The Settings connection test: `GET /health/auth` (docs/api-spec.md,
 * "Health"). 200 means the base URL and the key are both right, 401 means the
 * key is wrong, and a connection failure means the server was not reached.
 * The four-outcome message mapping lives in the Settings ViewModel. A
 * success carries the backend's reported version so version skew is legible
 * in the connection test rather than only in a failed decode (issue #195).
 */
class HealthAuthConnectionTester
    @Inject
    constructor(
        private val api: RecallyApi,
        @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) : ConnectionTester {
        override suspend fun test(): Result<String?> =
            withContext(ioDispatcher) {
                when (val result = apiCall { api.healthAuth() }) {
                    is Result.Success -> Result.Success(result.data.version)
                    Result.Unauthorized -> Result.Unauthorized
                    is Result.HttpError -> result
                    is Result.NetworkError -> result
                    is Result.UnexpectedError -> result
                }
            }
    }
