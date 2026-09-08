package dev.recally.data.remote

import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * Every endpoint in the screen → endpoint map (docs/android.md) against the
 * payloads in docs/api-spec.md. Unpaginated list endpoints return the full
 * set by design (api-spec.md, "List endpoints are unpaginated in v1").
 */
interface RecallyApi {
    // Health — the Settings connection test (200 = URL + key right).
    @GET("health/auth")
    suspend fun healthAuth(): HealthResponse

    // Reviews
    @GET("reviews/due")
    suspend fun dueCards(): DueCardsResponse

    @POST("reviews/{card_id}/rate")
    suspend fun rateCard(
        @Path("card_id") cardId: Long,
        @Body body: RateRequest,
    ): RateResponse

    @POST("reviews/rate-batch")
    suspend fun rateBatch(
        @Body body: RateBatchRequest,
    ): RateBatchResponse

    // Approval queue
    @GET("cards/pending")
    suspend fun pendingCards(
        @Query("status") status: String? = null,
        @Query("book_id") bookId: Long? = null,
        @Query("chapter") chapter: String? = null,
    ): PendingCardsResponse

    @POST("cards/{card_id}/approve")
    suspend fun approveCard(
        @Path("card_id") cardId: Long,
        @Body body: ApproveCardRequest,
    ): CardDto

    @POST("cards/{card_id}/reject")
    suspend fun rejectCard(
        @Path("card_id") cardId: Long,
        @Body body: RejectCardRequest,
    ): CardDto

    // Approved-card controls (ADR-008)
    @PATCH("cards/{card_id}")
    suspend fun editCard(
        @Path("card_id") cardId: Long,
        @Body body: EditCardRequest,
    ): CardDto

    @POST("cards/{card_id}/bury")
    suspend fun buryCard(
        @Path("card_id") cardId: Long,
    ): SuspendedUntilResponse

    @POST("cards/{card_id}/suspend")
    suspend fun suspendCard(
        @Path("card_id") cardId: Long,
    ): SuspendedUntilResponse

    @POST("cards/{card_id}/unsuspend")
    suspend fun unsuspendCard(
        @Path("card_id") cardId: Long,
    ): SuspendedUntilResponse

    // Decks & browsing
    @GET("decks")
    suspend fun decks(): DeckListResponse

    @GET("decks/{book_id}/cards")
    suspend fun deckCards(
        @Path("book_id") bookId: Long,
        @Query("chapter") chapter: String? = null,
    ): DeckCardsResponse

    // Stats
    @GET("stats")
    suspend fun stats(): StatsResponse

    // Devices — called on every app start and FCM token refresh.
    @POST("devices")
    suspend fun registerDevice(
        @Body body: DeviceRequest,
    ): DeviceResponse
}
