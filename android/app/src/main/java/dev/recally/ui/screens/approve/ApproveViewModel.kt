package dev.recally.ui.screens.approve

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.recally.di.IoDispatcher
import dev.recally.domain.repository.ApprovalRepository
import dev.recally.domain.repository.Result
import dev.recally.domain.repository.displayMessage
import dev.recally.ui.navigation.ARG_FILTER
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
 * "Offline-first sync"). [approveAllClean] adds a bulk path (issue #168);
 * it sends only `pending_review` ids, and the server refuses `needs_human`
 * per card besides (hard rule 1). Since issue #266 the pill tap arms a
 * confirmation dialog rather than calling it directly.
 */
@HiltViewModel
class ApproveViewModel
    @Inject
    constructor(
        private val approvalRepository: ApprovalRepository,
        @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
        savedStateHandle: SavedStateHandle,
    ) : ViewModel() {
        /**
         * The starting filter the entry point chose (issue #178). Applied to
         * the very first [ApproveUiState] rather than to a later update: a
         * flash of All before the Needs-you list arrives is the bug this
         * fixes. An absent or unrecognised argument falls back to All.
         */
        private val initialFilter: QueueFilter =
            savedStateHandle
                .get<String>(ARG_FILTER)
                ?.let { name -> QueueFilter.entries.firstOrNull { it.name == name } }
                ?: QueueFilter.ALL

        private val mutableUiState = MutableStateFlow(ApproveUiState(filter = initialFilter))
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
                                bulkApprovableCount = result.data.counts.pendingReview,
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
                    // The server answered and the answer could not be read —
                    // not a connectivity failure (issue #188).
                    is Result.UnexpectedError ->
                        mutableUiState.update {
                            it.copy(isLoading = false, errorMessage = result.displayMessage())
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
         * Save inline edits without deciding the card (issue #287, ADR-018):
         * `PATCH /cards/{id}` updates the wording and the card stays queued —
         * status, counts and FSRS state are untouched. A successful Save
         * rewrites the card's text in place and closes the editor; a failed
         * Save leaves the editor open with the input intact and surfaces the
         * error. Only [approveCard] and [rejectCard] remove the card.
         */
        fun editCard(
            cardId: Long,
            front: String,
            back: String,
        ) {
            val snapshot = mutableUiState.value
            if (snapshot.isOffline || snapshot.busyCardId != null) return
            viewModelScope.launch(exceptionHandler) {
                mutableUiState.update { it.copy(busyCardId = cardId, errorMessage = null) }
                val result =
                    withContext(ioDispatcher) { approvalRepository.saveCard(cardId, front, back) }
                mutableUiState.update {
                    when (result) {
                        is Result.Success ->
                            it.copy(
                                groups = updateCardText(it.groups, cardId, front, back),
                                busyCardId = null,
                                editingCardId = null,
                            )
                        Result.Unauthorized ->
                            it.copy(busyCardId = null, isUnauthorized = true)
                        is Result.NetworkError ->
                            it.copy(busyCardId = null, isOffline = true)
                        is Result.HttpError ->
                            it.copy(busyCardId = null, errorMessage = result.detail ?: MESSAGE_GENERIC)
                        is Result.UnexpectedError ->
                            it.copy(busyCardId = null, errorMessage = result.displayMessage())
                    }
                }
            }
        }

        /** Reject one card; the reason is optional (docs/android.md). */
        fun rejectCard(
            cardId: Long,
            reason: String? = null,
        ) = actOnCard(cardId) { approvalRepository.rejectCard(cardId, reason) }

        /**
         * Pill tap: arm the confirmation dialog instead of approving (issue
         * #266). One stray tap on "Approve N ready" approved 152 cards in a
         * minute; the same guards that gate [approveAllClean] apply here, so
         * the dialog cannot be armed while offline, mid-bulk, or with a card
         * busy. Cancel ([onDismissBulkApproveConfirm]) is a pure flag clear —
         * no repository call.
         */
        fun onBulkApproveClick() {
            val snapshot = mutableUiState.value
            if (snapshot.isOffline || snapshot.isBulkApproving || snapshot.busyCardId != null) return
            if (!snapshot.showBulkApprove) return
            mutableUiState.update { it.copy(showBulkApproveConfirm = true) }
        }

        /** Confirm the dialog: clear the flag, then run the bulk approve. */
        fun onConfirmBulkApprove() {
            mutableUiState.update { it.copy(showBulkApproveConfirm = false) }
            approveAllClean()
        }

        /** Dismiss the dialog with no repository call (issue #266). */
        fun onDismissBulkApproveConfirm() {
            mutableUiState.update { it.copy(showBulkApproveConfirm = false) }
        }

        /**
         * Bulk approve every clean card in the collection (issue #168).
         *
         * Sends only `pending_review` ids: `needs_human` cards are opened
         * individually (hard rule 1). The server refuses them per card as
         * well, so the rule holds even if a client ever gets this wrong — but
         * the client does not ask in the first place.
         *
         * A partial failure is not an error for the action: entries that come
         * back `ok = false` stay in the queue, so one stale id cannot defeat
         * clearing the backlog.
         *
         * Called from the confirm dialog only (issue #266); the pill tap goes
         * to [onBulkApproveClick] instead, so one stray tap cannot empty the
         * queue.
         */
        fun approveAllClean() {
            val snapshot = mutableUiState.value
            if (snapshot.isOffline || snapshot.isBulkApproving || snapshot.busyCardId != null) return
            val cleanIds =
                snapshot.groups
                    .flatMap { group -> group.cards }
                    .filterNot { it.isNeedsHuman }
                    .map { it.id }
            if (cleanIds.isEmpty()) return
            viewModelScope.launch(exceptionHandler) {
                mutableUiState.update { it.copy(isBulkApproving = true, errorMessage = null) }
                val result = withContext(ioDispatcher) { approvalRepository.approveBatch(cleanIds) }
                mutableUiState.update { state ->
                    when (result) {
                        is Result.Success -> {
                            val approved =
                                result.data
                                    .filter { it.ok }
                                    .map { it.cardId }
                                    .toSet()
                            val failed = result.data.count { !it.ok }
                            state.copy(
                                groups = approved.fold(state.groups) { groups, id -> removeCard(groups, id) },
                                pendingCount =
                                    state.pendingCount?.let { (it - approved.size).coerceAtLeast(0) },
                                bulkApprovableCount =
                                    state.bulkApprovableCount?.let { (it - approved.size).coerceAtLeast(0) },
                                expandedHighlightCardIds = state.expandedHighlightCardIds - approved,
                                isBulkApproving = false,
                                errorMessage =
                                    if (failed > 0) "$failed card${if (failed == 1) "" else "s"} could not be approved." else null,
                            )
                        }
                        Result.Unauthorized -> state.copy(isBulkApproving = false, isUnauthorized = true)
                        is Result.NetworkError -> state.copy(isBulkApproving = false, isOffline = true)
                        is Result.HttpError ->
                            state.copy(
                                isBulkApproving = false,
                                errorMessage = result.detail ?: MESSAGE_GENERIC,
                            )
                        is Result.UnexpectedError ->
                            state.copy(
                                isBulkApproving = false,
                                errorMessage = result.displayMessage(),
                            )
                    }
                }
            }
        }

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
                        is Result.UnexpectedError ->
                            it.copy(busyCardId = null, errorMessage = result.displayMessage())
                    }
                }
            }
        }

        private companion object {
            const val MESSAGE_GENERIC = "Something went wrong — try again."

            /** Rewrites the card's text in place; the card stays queued. */
            fun updateCardText(
                groups: List<ChapterGroup>,
                cardId: Long,
                front: String,
                back: String,
            ): List<ChapterGroup> =
                groups.map { group ->
                    group.copy(
                        cards =
                            group.cards.map { card ->
                                if (card.id == cardId) card.copy(front = front, back = back) else card
                            },
                    )
                }

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
