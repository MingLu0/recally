package dev.recally.data.repository

import dev.recally.data.remote.EditCardRequest
import dev.recally.data.remote.RateRequest
import dev.recally.data.remote.RecallyApi
import dev.recally.data.remote.apiCall
import dev.recally.di.IoDispatcher
import dev.recally.domain.model.RateOutcome
import dev.recally.domain.model.ReviewRating
import dev.recally.domain.repository.Result
import dev.recally.domain.repository.ReviewRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Posts review-session mutations (docs/android.md, *Screens → 2*). Ratings
 * go straight to `POST /reviews/{id}/rate` for now; step 4i inserts the Room
 * outbox in front of this so a rating is persisted the moment it is tapped
 * and flushed via `rate-batch`. Bury and edit stay online-only by design —
 * they are disabled offline rather than queued.
 */
class ReviewRepositoryImpl
    @Inject
    constructor(
        private val api: RecallyApi,
        @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) : ReviewRepository {
        override suspend fun rate(rating: ReviewRating): Result<RateOutcome> =
            withContext(ioDispatcher) {
                apiCall {
                    api
                        .rateCard(
                            cardId = rating.cardId,
                            body =
                                RateRequest(
                                    rating = rating.rating,
                                    responseMs = rating.responseMs,
                                    ratedAt = rating.ratedAt.toString(),
                                    deviceId = rating.deviceId,
                                ),
                        ).toDomain()
                }
            }

        override suspend fun bury(cardId: Long): Result<Unit> =
            withContext(ioDispatcher) {
                when (val result = apiCall { api.buryCard(cardId) }) {
                    is Result.Success -> Result.Success(Unit)
                    is Result.Unauthorized -> Result.Unauthorized
                    is Result.HttpError -> result
                    is Result.NetworkError -> result
                }
            }

        override suspend fun editCard(
            cardId: Long,
            front: String?,
            back: String?,
        ): Result<Unit> =
            withContext(ioDispatcher) {
                when (val result = apiCall { api.editCard(cardId, EditCardRequest(front = front, back = back)) }) {
                    is Result.Success -> Result.Success(Unit)
                    is Result.Unauthorized -> Result.Unauthorized
                    is Result.HttpError -> result
                    is Result.NetworkError -> result
                }
            }
    }
