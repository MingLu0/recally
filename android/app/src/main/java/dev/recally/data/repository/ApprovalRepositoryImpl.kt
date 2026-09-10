package dev.recally.data.repository

import dev.recally.data.remote.ApproveBatchRequest
import dev.recally.data.remote.ApproveCardRequest
import dev.recally.data.remote.RecallyApi
import dev.recally.data.remote.RejectCardRequest
import dev.recally.data.remote.apiCall
import dev.recally.di.IoDispatcher
import dev.recally.domain.model.ApproveBatchResult
import dev.recally.domain.model.PendingQueue
import dev.recally.domain.repository.ApprovalRepository
import dev.recally.domain.repository.Result
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Approval queue over the API only — no Room cache, because the queue
 * requires connectivity and its writes are never queued (docs/android.md,
 * "Offline-first sync"). Failures surface as [Result] cases, never throws.
 */
class ApprovalRepositoryImpl
    @Inject
    constructor(
        private val api: RecallyApi,
        @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) : ApprovalRepository {
        override suspend fun pendingCards(): Result<PendingQueue> =
            withContext(ioDispatcher) {
                apiCall { api.pendingCards().toDomain() }
            }

        override suspend fun approveCard(
            cardId: Long,
            front: String?,
            back: String?,
        ): Result<Unit> =
            withContext(ioDispatcher) {
                apiCall { api.approveCard(cardId, ApproveCardRequest(front = front, back = back)) }.toUnit()
            }

        override suspend fun approveBatch(cardIds: List<Long>): Result<List<ApproveBatchResult>> =
            withContext(ioDispatcher) {
                apiCall {
                    api.approveBatch(ApproveBatchRequest(cardIds = cardIds)).results.map { result ->
                        ApproveBatchResult(
                            cardId = result.cardId,
                            ok = result.ok,
                            detail = result.detail,
                        )
                    }
                }
            }

        override suspend fun rejectCard(
            cardId: Long,
            reason: String?,
        ): Result<Unit> =
            withContext(ioDispatcher) {
                apiCall { api.rejectCard(cardId, RejectCardRequest(reason = reason)) }.toUnit()
            }

        /** The updated card in the response is of no use to a queue that drops the row. */
        private fun <T> Result<T>.toUnit(): Result<Unit> =
            when (this) {
                is Result.Success -> Result.Success(Unit)
                is Result.Unauthorized -> Result.Unauthorized
                is Result.HttpError -> this
                is Result.NetworkError -> this
            }
    }
