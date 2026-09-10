package dev.recally.domain.repository

import java.io.IOException

/**
 * The sealed result every repository returns (docs/android.md, "Project
 * structure"). A 401 is its own case, not a generic failure: the UI answers
 * it with a "check settings" banner and must never crash on it.
 */
sealed interface Result<out T> {
    /**
     * [servedFromCache] is set only by a repository whose refresh failed and
     * answered from cache instead (CardRepository) — it is how the UI knows to
     * show the offline bar (design-system.md, "States"). A plain cache read is
     * not marked; only a *failed refresh* fallback is.
     */
    data class Success<T>(
        val data: T,
        val servedFromCache: Boolean = false,
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

    /**
     * A fault that is not the network: a malformed or unexpected payload, a
     * mapping failure, anything a repository did not anticipate. Distinct from
     * [NetworkError] because the UI reads that case as "offline" — relabelling
     * a decoding fault as a connectivity failure told the human the device was
     * offline while it was plainly connected (issue #188).
     */
    data class UnexpectedError(
        val cause: Throwable,
    ) : Result<Nothing>
}

/**
 * What a screen shows for an [Result.UnexpectedError]. The cause is a
 * programming-level fault the human can do nothing about, so the banner names
 * the shape of it and the log carries the detail — never the offline bar,
 * which would claim a connectivity problem that does not exist (issue #188).
 */
fun Result.UnexpectedError.displayMessage(): String = "Unexpected response from the server (${cause::class.simpleName ?: "error"})"
