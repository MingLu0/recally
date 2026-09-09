package dev.recally.ui.screens.approve

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.recally.di.IoDispatcher
import dev.recally.domain.repository.ApprovalRepository
import dev.recally.domain.repository.Result
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Approval queue ViewModel (docs/android.md, "Architecture"). Owns the one
 * [ApproveUiState]; instantiated at the Approve `NavHost` route entry.
 *
 * Requires connectivity: a network failure on load is the offline state, and
 * offline (or mid-action) approve/reject/edit are no-ops — those writes are
 * never queued, ratings are the only queued write (docs/android.md,
 * "Offline-first sync"). Cards are approved individually; there is no bulk
 * path (G4, scoped out in issue #59).
 */
@HiltViewModel
class ApproveViewModel
    @Inject
    constructor(
        private val approvalRepository: ApprovalRepository,
        @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) : ViewModel() {
        private val mutableUiState = MutableStateFlow(ApproveUiState())
        val uiState: StateFlow<ApproveUiState> = mutableUiState.asStateFlow()

        private val exceptionHandler =
            CoroutineExceptionHandler { _, _ ->
                mutableUiState.update {
                    it.copy(isLoading = false, busyCardId = null, errorMessage = MESSAGE_GENERIC)
                }
            }

        init {
            refresh()
        }

        fun refresh() {
            viewModelScope.launch(exceptionHandler) {
                mutableUiState.update { it.copy(isLoading = true, errorMessage = null) }
                when (val result = withContext(ioDispatcher) { approvalRepository.pendingCards() }) {
                    is Result.Success ->
                        mutableUiState.update {
                            it.copy(
                                groups = groupIntoChapters(result.data.cards),
                                pendingCount = result.data.counts.total,
                                isLoading = false,
                                isOffline = false,
                                isUnauthorized = false,
                            )
                        }
                    Result.Unauthorized ->
                        mutableUiState.update { it.copy(isLoading = false, isUnauthorized = true) }
                    is Result.NetworkError ->
                        mutableUiState.update { it.copy(isLoading = false, isOffline = true) }
                    is Result.HttpError ->
                        mutableUiState.update {
                            it.copy(isLoading = false, errorMessage = result.detail ?: MESSAGE_GENERIC)
                        }
                }
            }
        }

        fun onFilterChange(filter: QueueFilter) {
            mutableUiState.update { it.copy(filter = filter) }
        }

        fun onToggleHighlights(cardId: Long) {
            mutableUiState.update {
                it.copy(
                    expandedHighlightCardIds =
                        if (cardId in it.expandedHighlightCardIds) {
                            it.expandedHighlightCardIds - cardId
                        } else {
                            it.expandedHighlightCardIds + cardId
                        },
                )
            }
        }

        fun onStartEdit(cardId: Long) {
            mutableUiState.update { it.copy(editingCardId = cardId) }
        }

        fun onDismissEdit() {
            mutableUiState.update { it.copy(editingCardId = null) }
        }

        /** Approve one card as written. */
        fun approveCard(cardId: Long) = actOnCard(cardId) { approvalRepository.approveCard(cardId) }

        /**
         * Edit inline, then approve with the edits — edits before approval
         * belong to the approve endpoint, not `PATCH` (docs/api-spec.md).
         */
        fun editCard(
            cardId: Long,
            front: String,
            back: String,
        ) = actOnCard(cardId) { approvalRepository.approveCard(cardId, front, back) }

        /** Reject one card; the reason is optional (docs/android.md). */
        fun rejectCard(
            cardId: Long,
            reason: String? = null,
        ) = actOnCard(cardId) { approvalRepository.rejectCard(cardId, reason) }

        /**
         * Runs one card action. A success drops the card from the queue
         * locally — no refetch. Offline or while another card is busy, the
         * action is disabled rather than queued.
         */
        private fun actOnCard(
            cardId: Long,
            call: suspend () -> Result<Unit>,
        ) {
            val snapshot = mutableUiState.value
            if (snapshot.isOffline || snapshot.busyCardId != null) return
            viewModelScope.launch(exceptionHandler) {
                mutableUiState.update { it.copy(busyCardId = cardId, errorMessage = null) }
                val result = withContext(ioDispatcher) { call() }
                mutableUiState.update {
                    when (result) {
                        is Result.Success ->
                            it.copy(
                                groups = removeCard(it.groups, cardId),
                                pendingCount = it.pendingCount?.let { count -> (count - 1).coerceAtLeast(0) },
                                busyCardId = null,
                                editingCardId = null,
                                expandedHighlightCardIds = it.expandedHighlightCardIds - cardId,
                            )
                        Result.Unauthorized ->
                            it.copy(busyCardId = null, isUnauthorized = true)
                        is Result.NetworkError ->
                            it.copy(busyCardId = null, isOffline = true)
                        is Result.HttpError ->
                            it.copy(busyCardId = null, errorMessage = result.detail ?: MESSAGE_GENERIC)
                    }
                }
            }
        }

        private companion object {
            const val MESSAGE_GENERIC = "Something went wrong — try again."

            /** Drops the card and any group it leaves empty. */
            fun removeCard(
                groups: List<ChapterGroup>,
                cardId: Long,
            ): List<ChapterGroup> =
                groups.mapNotNull { group ->
                    val kept = group.cards.filterNot { it.id == cardId }
                    if (kept.size == group.cards.size) {
                        group
                    } else {
                        kept.takeIf { it.isNotEmpty() }?.let { group.copy(cards = it) }
                    }
                }
        }
    }
