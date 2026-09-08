package dev.recally.domain.repository

import java.io.IOException

/**
 * The sealed result every repository returns (docs/android.md, "Project
 * structure"). A 401 is its own case, not a generic failure: the UI answers
 * it with a "check settings" banner and must never crash on it.
 */
sealed interface Result<out T> {
    data class Success<T>(
        val data: T,
    ) : Result<T>

    /** 401 — the API key is wrong. Distinct from every other failure. */
    data object Unauthorized : Result<Nothing>

    /** Any other non-2xx, carrying the problem+json detail when there is one. */
    data class HttpError(
        val status: Int,
        val detail: String?,
    ) : Result<Nothing>

    /** The server could not be reached at all. */
    data class NetworkError(
        val cause: IOException,
    ) : Result<Nothing>
}
