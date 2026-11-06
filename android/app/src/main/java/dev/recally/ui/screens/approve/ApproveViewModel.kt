package dev.recally.ui.screens.approve

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.recally.di.IoDispatcher
import dev.recally.domain.model.DroppedUnits
import dev.recally.domain.model.PendingCard
import dev.recally.domain.model.PendingQueue
import dev.recally.domain.repository.ApprovalRepository
import dev.recally.domain.repository.RejectReasonDraftsRepository
import dev.recally.domain.repository.Result
import dev.recally.domain.repository.displayMessage
import dev.recally.ui.navigation.ARG_FILTER
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.channels.Channel
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
 *
 * Also owns the reject-reason drafts (issue #343): loading them into
 * [ApproveUiState] before the queue renders, saving every keystroke in
 * typing order, and clearing a card's draft once the card leaves the queue.
 * Drafts are device-local convenience state — the repository never throws
 * into the queue, and typing never touches the approval repository.
 */
@HiltViewModel
class ApproveViewModel
    @Inject
    constructor(
        private val approvalRepository: ApprovalRepository,
        private val rejectReasonDraftsRepository: RejectReasonDraftsRepository,
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

        /** A Dropped action that throws keeps its item and frees the Dropped actions. */
        private val dropExceptionHandler =
            CoroutineExceptionHandler { _, _ ->
                mutableUiState.update { state ->
                    val unitId = state.busyDropUnitId
                    val idle = state.copy(busyDropUnitId = null, generatingUnitId = null)
                    if (unitId == null) idle.copy(errorMessage = MESSAGE_GENERIC) else idle.withDropError(unitId, MESSAGE_GENERIC)
                }
            }

        /**
         * Draft writes funnel through one actor coroutine so they land in the
         * order they were typed (issue #343). DataStore serialises edits to a
         * file, but two ad-hoc launches could still reach it out of order;
         * the last keystroke must win.
         */
        private sealed interface DraftCommand {
            data class Save(
                val cardId: Long,
                val reason: String,
            ) : DraftCommand

            data class Clear(
                val cardIds: Set<Long>,
            ) : DraftCommand
        }

        private val draftCommands = Channel<DraftCommand>(Channel.BUFFERED)

        init {
            viewModelScope.launch(ioDispatcher) {
                for (command in draftCommands) {
                    try {
                        when (command) {
                            is DraftCommand.Save ->
                                rejectReasonDraftsRepository.save(command.cardId, command.reason)
                            is DraftCommand.Clear -> rejectReasonDraftsRepository.clear(command.cardIds)
                        }
                    } catch (_: Exception) {
                        // A draft is a convenience: a storage failure must not
                        // block the queue or a rejection (issue #343).
                    }
                }
            }
            viewModelScope.launch(exceptionHandler) {
                loadDrafts()
                refresh()
            }
        }

        /** Stored drafts go into the UiState before the queue renders, so a dialog cannot open empty and then be overwritten (issue #343). */
        private suspend fun loadDrafts() {
            val drafts =
                try {
                    rejectReasonDraftsRepository.load()
                } catch (_: Exception) {
                    // Convenience-only storage: a read failure yields no drafts.
                    emptyMap()
                }
            mutableUiState.update { it.copy(rejectReasonDrafts = drafts) }
        }

        /**
         * Loads the queue and the Curator's drops together (issue #365): one
         * screen entry, one refresh, both lists. The queue's result decides the
         * screen-level state (offline, 401, error) exactly as before; a drops
         * failure adds its own, and the queue's error message wins a tie.
         */
        fun refresh() {
            viewModelScope.launch(exceptionHandler) {
                mutableUiState.update { it.copy(isLoading = true, errorMessage = null) }
                val pending = withContext(ioDispatcher) { approvalRepository.pendingCards() }
                val dropped = withContext(ioDispatcher) { approvalRepository.droppedUnits() }
                mutableUiState.update { applyDropped(applyPending(it, pending), dropped).copy(isLoading = false) }
            }
        }

        private fun applyPending(
            state: ApproveUiState,
            result: Result<PendingQueue>,
        ): ApproveUiState =
            when (result) {
                is Result.Success ->
                    state.copy(
                        groups = groupIntoChapters(result.data.cards),
                        pendingCount = result.data.counts.total,
                        bulkApprovableCount = result.data.counts.pendingReview,
                        isOffline = false,
                        isUnauthorized = false,
                    )
                Result.Unauthorized -> state.copy(isUnauthorized = true)
                is Result.NetworkError -> state.copy(isOffline = true)
                is Result.HttpError -> state.copy(errorMessage = result.detail ?: MESSAGE_GENERIC)
                // The server answered and the answer could not be read —
                // not a connectivity failure (issue #188).
                is Result.UnexpectedError -> state.copy(errorMessage = result.displayMessage())
            }

        private fun applyDropped(
            state: ApproveUiState,
            result: Result<DroppedUnits>,
        ): ApproveUiState =
            when (result) {
                is Result.Success ->
                    state.copy(
                        droppedGroups = groupDropsIntoChapters(result.data.units),
                        droppedCount = result.data.count,
                        dropErrors = emptyMap(),
                    )
                Result.Unauthorized -> state.copy(isUnauthorized = true)
                is Result.NetworkError -> state.copy(isOffline = true)
                is Result.HttpError -> state.copy(errorMessage = state.errorMessage ?: result.detail ?: MESSAGE_GENERIC)
                is Result.UnexpectedError -> state.copy(errorMessage = state.errorMessage ?: result.displayMessage())
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
         * Every keystroke in a reject dialog (issue #343): update the card's
         * draft in the UiState and persist it in typing order. A blank text
         * removes the draft. This never touches the approval repository —
         * typing saves a device-local draft; only [rejectCard] sends text to
         * the server.
         */
        fun onRejectReasonChange(
            cardId: Long,
            reason: String,
        ) {
            mutableUiState.update {
                it.copy(
                    rejectReasonDrafts =
                        if (reason.isBlank()) {
                            it.rejectReasonDrafts - cardId
                        } else {
                            it.rejectReasonDrafts + (cardId to reason)
                        },
                )
            }
            draftCommands.trySend(DraftCommand.Save(cardId, reason))
        }

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
                val approved =
                    (result as? Result.Success)
                        ?.data
                        ?.filter { it.ok }
                        ?.map { it.cardId }
                        ?.toSet()
                        .orEmpty()
                // Only the cards that actually left the queue lose their drafts.
                if (approved.isNotEmpty()) draftCommands.trySend(DraftCommand.Clear(approved))
                mutableUiState.update { state ->
                    when (result) {
                        is Result.Success -> {
                            val failed = result.data.count { !it.ok }
                            state.copy(
                                groups = approved.fold(state.groups) { groups, id -> removeCard(groups, id) },
                                pendingCount =
                                    state.pendingCount?.let { (it - approved.size).coerceAtLeast(0) },
                                bulkApprovableCount =
                                    state.bulkApprovableCount?.let { (it - approved.size).coerceAtLeast(0) },
                                expandedHighlightCardIds = state.expandedHighlightCardIds - approved,
                                rejectReasonDrafts = state.rejectReasonDrafts - approved,
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
         * locally — no refetch — and removes its reject-reason draft, if any
         * (issue #343); a failure keeps the draft so a typed reason is not
         * lost. Offline or while another card is busy, the action is disabled
         * rather than queued.
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
                if (result is Result.Success) draftCommands.trySend(DraftCommand.Clear(setOf(cardId)))
                mutableUiState.update {
                    when (result) {
                        is Result.Success ->
                            it.copy(
                                groups = removeCard(it.groups, cardId),
                                pendingCount = it.pendingCount?.let { count -> (count - 1).coerceAtLeast(0) },
                                busyCardId = null,
                                editingCardId = null,
                                expandedHighlightCardIds = it.expandedHighlightCardIds - cardId,
                                rejectReasonDrafts = it.rejectReasonDrafts - cardId,
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

        /**
         * Dismiss a drop — the Curator was right (issue #365). Called from the
         * confirm dialog only. Requires connectivity and is never queued; a
         * failure keeps the item and names the error on it.
         */
        fun dismissDrop(unitId: Long) =
            actOnDrop(unitId) {
                when (val result = approvalRepository.dismissDroppedUnit(unitId)) {
                    is Result.Success -> Result.Success(emptyList())
                    else -> result.failureAs()
                }
            }

        /**
         * Generate cards for a drop through the Writer ⇄ Critic loop (issue
         * #365). The call answers after the cards are written (often 30–90 s);
         * meanwhile the item shows progress and every Dropped action waits, so
         * a second tap cannot generate twice. On success the item leaves
         * Dropped and the returned cards join the queue groups — none approved
         * (hard rule 1).
         */
        fun generateDrop(unitId: Long) =
            actOnDrop(unitId, isGenerate = true) {
                approvalRepository.generateCardsForDroppedUnit(unitId)
            }

        /** Open the "Write card" editor on a drop; one editor at a time, never while busy. */
        fun onStartManualCard(unitId: Long) {
            if (!mutableUiState.value.dropActionsEnabled) return
            mutableUiState.update { it.copy(manualCardDraft = ManualCardDraft(unitId = unitId)) }
        }

        /** Every edit to the open editor; the server's last 422 clears once the text changes. */
        fun onManualCardChange(draft: ManualCardDraft) {
            mutableUiState.update { state ->
                val open = state.manualCardDraft ?: return@update state
                if (open.unitId != draft.unitId) return@update state
                state.copy(manualCardDraft = draft.copy(error = null))
            }
        }

        fun onCancelManualCard() {
            mutableUiState.update { it.copy(manualCardDraft = null) }
        }

        /**
         * Add the typed card to the queue (`POST /units/{id}/cards`). It lands
         * `pending_review` and still needs Approve (hard rule 1). A 422 keeps
         * the editor open with the typed text and the server's `detail`.
         */
        fun submitManualCard() {
            val draft = mutableUiState.value.manualCardDraft ?: return
            if (!draft.canSubmit) return
            actOnDrop(draft.unitId) {
                when (
                    val result =
                        approvalRepository.createCardForDroppedUnit(draft.unitId, draft.type, draft.front, draft.back)
                ) {
                    is Result.Success -> Result.Success(listOf(result.data))
                    else -> result.failureAs()
                }
            }
        }

        /**
         * Runs one Dropped action. A success removes the unit from Dropped and
         * inserts the cards it produced into the queue locally — no refetch.
         * Offline, or while another Dropped action runs, nothing happens: the
         * action is disabled, never queued (docs/android.md, "Ratings are the
         * only queued write").
         */
        private fun actOnDrop(
            unitId: Long,
            isGenerate: Boolean = false,
            call: suspend () -> Result<List<PendingCard>>,
        ) {
            if (!mutableUiState.value.dropActionsEnabled) return
            mutableUiState.update {
                it.copy(
                    busyDropUnitId = unitId,
                    generatingUnitId = if (isGenerate) unitId else null,
                    dropErrors = it.dropErrors - unitId,
                    errorMessage = null,
                )
            }
            viewModelScope.launch(dropExceptionHandler) {
                val result = withContext(ioDispatcher) { call() }
                mutableUiState.update { state ->
                    val idle = state.copy(busyDropUnitId = null, generatingUnitId = null)
                    when (result) {
                        is Result.Success -> resolveDrop(idle, unitId, result.data)
                        Result.Unauthorized -> idle.copy(isUnauthorized = true)
                        is Result.NetworkError -> idle.copy(isOffline = true)
                        is Result.HttpError -> idle.withDropError(unitId, result.detail ?: MESSAGE_GENERIC)
                        is Result.UnexpectedError -> idle.withDropError(unitId, result.displayMessage())
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

            /** The unit leaves Dropped; its cards join the queue, counted like any queued card. */
            fun resolveDrop(
                state: ApproveUiState,
                unitId: Long,
                cards: List<PendingCard>,
            ): ApproveUiState =
                state.copy(
                    droppedGroups = removeDrop(state.droppedGroups, unitId),
                    droppedCount = state.droppedCount?.let { (it - 1).coerceAtLeast(0) },
                    groups = insertCards(state.groups, cards),
                    pendingCount = state.pendingCount?.let { it + cards.size } ?: cards.size,
                    bulkApprovableCount =
                        cards.count { !it.isNeedsHuman }.let { added -> state.bulkApprovableCount?.plus(added) ?: added },
                    manualCardDraft = state.manualCardDraft?.takeIf { it.unitId != unitId },
                    dropErrors = state.dropErrors - unitId,
                )

            /**
             * A failed action keeps the unit listed. A 422 on the open editor
             * lands inline in it, keeping the typed text; anything else is named
             * on the item.
             */
            fun ApproveUiState.withDropError(
                unitId: Long,
                message: String,
            ): ApproveUiState {
                val draft = manualCardDraft
                return if (draft != null && draft.unitId == unitId) {
                    copy(manualCardDraft = draft.copy(error = message))
                } else {
                    copy(dropErrors = dropErrors + (unitId to message))
                }
            }

            fun removeDrop(
                groups: List<DroppedChapterGroup>,
                unitId: Long,
            ): List<DroppedChapterGroup> =
                groups.mapNotNull { group ->
                    val kept = group.units.filterNot { it.id == unitId }
                    if (kept.size == group.units.size) {
                        group
                    } else {
                        kept.takeIf { it.isNotEmpty() }?.let { group.copy(units = it) }
                    }
                }

            /**
             * Places each card in its chapter: appended to the last run of the
             * same (book, chapter), or as a new group where the server's
             * (book, chapter) order puts it. Export position within a chapter
             * is not known client-side, so a card lands at its chapter's end;
             * the next refresh restores the exact server order.
             */
            fun insertCards(
                groups: List<ChapterGroup>,
                cards: List<PendingCard>,
            ): List<ChapterGroup> =
                cards.fold(groups) { current, card ->
                    val sameChapter = current.indexOfLast { it.bookId == card.bookId && it.chapter == card.chapter }
                    if (sameChapter >= 0) {
                        current.toMutableList().apply {
                            this[sameChapter] = this[sameChapter].copy(cards = this[sameChapter].cards + card)
                        }
                    } else {
                        val newGroup = ChapterGroup(bookId = card.bookId, book = card.book, chapter = card.chapter, cards = listOf(card))
                        val position =
                            current.indexOfFirst { group ->
                                compareValuesBy(group, newGroup, ChapterGroup::book, ChapterGroup::chapter) > 0
                            }
                        current.toMutableList().apply { add(if (position < 0) size else position, newGroup) }
                    }
                }

            /** A non-success [Result] of one type re-typed as another, for the action plumbing. */
            fun <T> Result<*>.failureAs(): Result<T> =
                when (this) {
                    is Result.Success -> error("failureAs called on a success")
                    Result.Unauthorized -> Result.Unauthorized
                    is Result.HttpError -> this
                    is Result.NetworkError -> this
                    is Result.UnexpectedError -> this
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
