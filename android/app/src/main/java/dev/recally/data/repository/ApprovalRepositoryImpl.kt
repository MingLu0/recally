package dev.recally.data.repository

import dev.recally.data.remote.ApproveBatchRequest
import dev.recally.data.remote.ApproveCardRequest
import dev.recally.data.remote.EditCardRequest
import dev.recally.data.remote.HandWrittenCardRequest
import dev.recally.data.remote.RecallyApi
import dev.recally.data.remote.RejectCardRequest
import dev.recally.data.remote.apiCall
import dev.recally.di.IngestApi
import dev.recally.di.IoDispatcher
import dev.recally.domain.model.ApproveBatchResult
import dev.recally.domain.model.DroppedUnits
import dev.recally.domain.model.PendingCard
import dev.recally.domain.model.PendingQueue
import dev.recally.domain.repository.ApprovalRepository
import dev.recally.domain.repository.Result
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Approval queue — and the Curator's drops beside it (issue #365) — over the API only — no Room cache, because the queue
 * requires connectivity and its writes are never queued (docs/android.md,
 * "Offline-first sync"). Failures surface as [Result] cases, never throws.
 */
class ApprovalRepositoryImpl
    @Inject
    constructor(
        private val api: RecallyApi,
        @IngestApi private val longReadApi: RecallyApi,
        @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) : ApprovalRepository {
        override suspend fun pendingCards(): Result<PendingQueue> =
            withContext(ioDispatcher) {
                apiCall { api.pendingCards().toDomain() }
            }

        override suspend fun approveCard(cardId: Long): Result<Unit> =
            withContext(ioDispatcher) {
                // The Retrofit boundary keeps the server's optional
                // approve-with-edits payload; the app sends it empty.
                apiCall { api.approveCard(cardId, ApproveCardRequest()) }.toUnit()
            }

        override suspend fun saveCard(
            cardId: Long,
            front: String,
            back: String,
        ): Result<Unit> =
            withContext(ioDispatcher) {
                apiCall { api.editCard(cardId, EditCardRequest(front = front, back = back)) }.toUnit()
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

        override suspend fun droppedUnits(): Result<DroppedUnits> =
            withContext(ioDispatcher) {
                apiCall { api.droppedUnits().toDomain() }
            }

        override suspend fun dismissDroppedUnit(unitId: Long): Result<Unit> =
            withContext(ioDispatcher) {
                apiCall { api.dismissUnit(unitId) }.toUnit()
            }

        /**
         * The long-read client (issue #365): the server answers after the
         * Writer ⇄ Critic loop, which outlasts OkHttp's default read timeout.
         */
        override suspend fun generateCardsForDroppedUnit(unitId: Long): Result<List<PendingCard>> =
            withContext(ioDispatcher) {
                apiCall { longReadApi.generateUnitCards(unitId).cards.map { it.toDomain() } }
            }

        override suspend fun createCardForDroppedUnit(
            unitId: Long,
            type: String,
            front: String,
            back: String,
        ): Result<PendingCard> =
            withContext(ioDispatcher) {
                apiCall {
                    api.createUnitCard(unitId, HandWrittenCardRequest(type = type, front = front, back = back)).toDomain()
                }
            }

        /** The updated card in the response is of no use to a queue that drops the row. */
        private fun <T> Result<T>.toUnit(): Result<Unit> =
            when (this) {
                is Result.Success -> Result.Success(Unit)
                is Result.Unauthorized -> Result.Unauthorized
                is Result.HttpError -> this
                is Result.NetworkError -> this
                is Result.UnexpectedError -> this
            }
    }
