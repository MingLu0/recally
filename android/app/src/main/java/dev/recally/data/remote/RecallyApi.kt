package dev.recally.data.remote

import retrofit2.http.GET

/**
 * Retrofit service for the backend (docs/api-spec.md).
 *
 * Step 4d declares only the endpoints its repositories consume; step 4c
 * (#54) extends this same service with the full screen → endpoint map, the
 * X-API-Key interceptor and the runtime-configured base URL.
 */
interface RecallyApi {
    @GET("reviews/due")
    suspend fun getDueCards(): DueSummaryDto

    @GET("decks")
    suspend fun getDecks(): DecksDto
}
