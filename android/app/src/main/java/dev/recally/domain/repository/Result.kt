package dev.recally.domain.repository

/**
 * Repositories return this rather than throwing (docs/android.md,
 * "Repositories own the data layer"); the ViewModel maps it into UiState.
 *
 * [Failure.httpStatus] keeps HTTP failures distinguishable — a 401
 * ([Failure.isUnauthorized]) must surface as a "check settings" banner, not a
 * generic error (docs/android.md, "Connecting to the backend").
 */
sealed interface Result<out T> {
    data class Success<T>(
        val value: T,
    ) : Result<T>

    data class Failure(
        val message: String,
        val httpStatus: Int? = null,
        val cause: Throwable? = null,
    ) : Result<Nothing> {
        val isUnauthorized: Boolean get() = httpStatus == 401
    }
}
