package dev.recally.data.remote

import dev.recally.domain.repository.Result
import kotlinx.serialization.json.Json
import retrofit2.HttpException
import java.io.IOException

/**
 * Maps a Retrofit call into the sealed [Result] type. A 401 is its own case —
 * the UI answers it with a "check settings" banner and never crashes — and a
 * problem+json body (`{ "status": 422, "detail": "..." }`, docs/api-spec.md,
 * "Errors") surfaces its `detail` verbatim.
 */
suspend fun <T> apiCall(block: suspend () -> T): Result<T> =
    try {
        Result.Success(block())
    } catch (exception: HttpException) {
        if (exception.code() == HTTP_UNAUTHORIZED) {
            Result.Unauthorized
        } else {
            Result.HttpError(
                status = exception.code(),
                detail = problemDetail(exception),
            )
        }
    } catch (exception: IOException) {
        Result.NetworkError(exception)
    }

private const val HTTP_UNAUTHORIZED = 401

private val problemJson = Json { ignoreUnknownKeys = true }

@kotlinx.serialization.Serializable
private data class ProblemBody(
    val status: Int? = null,
    val detail: String? = null,
)

private fun problemDetail(exception: HttpException): String? {
    val body = exception.response()?.errorBody()?.string() ?: return null
    return runCatching { problemJson.decodeFromString<ProblemBody>(body).detail }.getOrNull()
}
