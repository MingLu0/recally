package dev.recally.ui.screens.approve

import androidx.lifecycle.SavedStateHandle
import dev.recally.domain.model.ApproveBatchResult
import dev.recally.domain.model.DroppedUnit
import dev.recally.domain.model.DroppedUnits
import dev.recally.domain.model.PendingCard
import dev.recally.domain.model.PendingCounts
import dev.recally.domain.model.PendingQueue
import dev.recally.domain.repository.ApprovalRepository
import dev.recally.domain.repository.RejectReasonDraftsRepository
import dev.recally.domain.repository.Result
import dev.recally.ui.navigation.ARG_FILTER
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

/**
 * ViewModel gate for roadmap step 4h (issue #59). Fake repository,
 * TestDispatcher for Main (docs/android.md, "Tests").
 *
 * Several tests here are negative assertions over the public surface (no bulk
 * approve, no pending count, no truncation repair). They use Java reflection
 * rather than kotlin-reflect, which is not on the classpath.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ApproveViewModelTest {
    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setMainDispatcher() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun resetMainDispatcher() {
        Dispatchers.resetMain()
    }

    /** Records every mutation; a call that must not happen is a call recorded. */
    private class FakeApprovalRepository(
        var pendingResult: Result<PendingQueue>,
    ) : ApprovalRepository {
        var pendingCalls = 0

        /** Optional hook that runs inside the pendingCards() call, for ordering proofs. */
        var onPendingCards: (() -> Unit)? = null

        val approveCalls = mutableListOf<Long>()
        var approveResult: Result<Unit> = Result.Success(Unit)

        data class SaveCall(
            val cardId: Long,
            val front: String,
            val back: String,
        )

        val saveCalls = mutableListOf<SaveCall>()
        var saveResult: Result<Unit> = Result.Success(Unit)

        data class RejectCall(
            val cardId: Long,
            val reason: String?,
        )

        val rejectCalls = mutableListOf<RejectCall>()
        var rejectResult: Result<Unit> = Result.Success(Unit)

        override suspend fun pendingCards(): Result<PendingQueue> {
            pendingCalls++
            onPendingCards?.invoke()
            return pendingResult
        }

        override suspend fun approveCard(cardId: Long): Result<Unit> {
            approveCalls += cardId
            return approveResult
        }

        override suspend fun saveCard(
            cardId: Long,
            front: String,
            back: String,
        ): Result<Unit> {
            saveCalls += SaveCall(cardId, front, back)
            return saveResult
        }

        override suspend fun rejectCard(
            cardId: Long,
            reason: String?,
        ): Result<Unit> {
            rejectCalls += RejectCall(cardId, reason)
            return rejectResult
        }

        val batchCalls = mutableListOf<List<Long>>()
        var batchResult: (List<Long>) -> Result<List<ApproveBatchResult>> = { ids ->
            Result.Success(ids.map { ApproveBatchResult(cardId = it, ok = true) })
        }

        override suspend fun approveBatch(cardIds: List<Long>): Result<List<ApproveBatchResult>> {
            batchCalls += cardIds
            return batchResult(cardIds)
        }

        // --- Dropped units (issue #365) ---

        var droppedResult: Result<DroppedUnits> = Result.Success(DroppedUnits(emptyList(), 0))
        var droppedCalls = 0

        override suspend fun droppedUnits(): Result<DroppedUnits> {
            droppedCalls++
            return droppedResult
        }

        val dismissCalls = mutableListOf<Long>()
        var dismissResult: Result<Unit> = Result.Success(Unit)

        override suspend fun dismissDroppedUnit(unitId: Long): Result<Unit> {
            dismissCalls += unitId
            return dismissResult
        }

        val generateCalls = mutableListOf<Long>()
        var generateResult: Result<List<PendingCard>> = Result.Success(emptyList())

        /** When set, generate suspends until completed — the 30–90 s server call, held open. */
        var generateGate: CompletableDeferred<Unit>? = null

        override suspend fun generateCardsForDroppedUnit(unitId: Long): Result<List<PendingCard>> {
            generateCalls += unitId
            generateGate?.await()
            return generateResult
        }

        data class ManualCall(
            val unitId: Long,
            val type: String,
            val front: String,
            val back: String,
        )

        val manualCalls = mutableListOf<ManualCall>()
        var manualResult: Result<PendingCard> = Result.HttpError(status = 500, detail = "unset")

        override suspend fun createCardForDroppedUnit(
            unitId: Long,
            type: String,
            front: String,
            back: String,
        ): Result<PendingCard> {
            manualCalls += ManualCall(unitId, type, front, back)
            return manualResult
        }
    }

    /** In-memory stand-in for the draft store; [writeLog] records saves in arrival order. */
    private class FakeRejectReasonDraftsRepository : RejectReasonDraftsRepository {
        val drafts = mutableMapOf<Long, String>()
        val writeLog = mutableListOf<Pair<Long, String>>()
        var loadCount = 0

        override suspend fun load(): Map<Long, String> {
            loadCount++
            return drafts.toMap()
        }

        override suspend fun save(
            cardId: Long,
            reason: String,
        ) {
            writeLog += cardId to reason
            if (reason.isBlank()) {
                drafts -= cardId
            } else {
                drafts[cardId] = reason
            }
        }

        override suspend fun clear(cardIds: Set<Long>) {
            cardIds.forEach { drafts -= it }
        }
    }

    /** One fake per test instance (JUnit constructs a fresh instance per test). */
    private val draftsRepository = FakeRejectReasonDraftsRepository()

    private fun viewModelWith(
        repository: FakeApprovalRepository,
        savedStateHandle: SavedStateHandle = SavedStateHandle(),
    ): ApproveViewModel =
        ApproveViewModel(
            approvalRepository = repository,
            rejectReasonDraftsRepository = draftsRepository,
            ioDispatcher = testDispatcher,
            savedStateHandle = savedStateHandle,
        )

    @Test
    fun test_initial_filter_defaults_to_all() =
        runTest {
            // Every existing entry point navigates to Approve with no filter
            // argument; those must be unchanged by issue #178.
            val viewModel = viewModelWith(FakeApprovalRepository(Result.Success(PendingQueue(emptyList(), PendingCounts(0, 0)))))

            assertEquals(QueueFilter.ALL, viewModel.uiState.value.filter)
        }

    @Test
    fun test_initial_filter_honours_needs_you_argument() =
        runTest {
            // The assertion is on the FIRST emitted state, before any coroutine
            // settles: a flash of ALL that corrects itself is the bug (#178),
            // not the fix.
            val viewModel =
                viewModelWith(
                    repository = FakeApprovalRepository(Result.Success(PendingQueue(emptyList(), PendingCounts(0, 0)))),
                    savedStateHandle = SavedStateHandle(mapOf(ARG_FILTER to QueueFilter.NEEDS_YOU.name)),
                )

            assertEquals(QueueFilter.NEEDS_YOU, viewModel.uiState.value.filter)
        }

    private fun loadedViewModel(
        cards: List<PendingCard>,
        counts: PendingCounts =
            PendingCounts(
                pendingReview = cards.count { !it.isNeedsHuman },
                needsHuman = cards.count { it.isNeedsHuman },
            ),
    ): Pair<ApproveViewModel, FakeApprovalRepository> {
        val repository = FakeApprovalRepository(Result.Success(PendingQueue(cards, counts)))
        return viewModelWith(repository) to repository
    }

    @Test
    fun test_bulk_approve_sends_only_pending_review_ids() =
        runTest {
            // Hard rule 1: needs_human never enters FSRS via a bulk path. The
            // server refuses them too, but the client must not even ask.
            val (viewModel, repository) =
                loadedViewModel(
                    listOf(
                        pendingCard(id = 1),
                        pendingCard(id = 2, status = PendingCard.STATUS_NEEDS_HUMAN),
                        pendingCard(id = 3),
                    ),
                )
            advanceUntilIdle()

            viewModel.approveAllClean()
            advanceUntilIdle()

            assertEquals(listOf(listOf(1L, 3L)), repository.batchCalls)
            assertEquals(false, repository.batchCalls.single().contains(2L))
        }

    @Test
    fun test_pill_tap_sets_confirm_flag_and_does_not_call_the_repository() =
        runTest {
            // Issue #266: a stray tap on "Approve N ready" approved 152 cards
            // in one minute; the tap must arm a confirmation dialog and never
            // the repository call itself.
            val (viewModel, repository) =
                loadedViewModel(
                    listOf(
                        pendingCard(id = 1),
                        pendingCard(id = 2),
                        pendingCard(id = 3, status = PendingCard.STATUS_NEEDS_HUMAN),
                    ),
                )
            advanceUntilIdle()

            viewModel.onBulkApproveClick()
            advanceUntilIdle()

            assertTrue(
                "the pill tap arms the confirmation dialog",
                viewModel.uiState.value.showBulkApproveConfirm,
            )
            assertTrue(
                "the pill tap must not call the repository (negative, #266)",
                repository.batchCalls.isEmpty(),
            )
        }

    @Test
    fun test_confirm_runs_bulk_approve() =
        runTest {
            val (viewModel, repository) =
                loadedViewModel(
                    listOf(
                        pendingCard(id = 1),
                        pendingCard(id = 2),
                        pendingCard(id = 3, status = PendingCard.STATUS_NEEDS_HUMAN),
                    ),
                )
            advanceUntilIdle()

            viewModel.onBulkApproveClick()
            advanceUntilIdle()
            assertTrue(viewModel.uiState.value.showBulkApproveConfirm)

            viewModel.onConfirmBulkApprove()
            advanceUntilIdle()

            assertEquals(
                "confirming approves exactly the clean ids — needs_human stays out (hard rule 1)",
                listOf(listOf(1L, 2L)),
                repository.batchCalls,
            )
            assertFalse(
                "confirming clears the dialog flag",
                viewModel.uiState.value.showBulkApproveConfirm,
            )
        }

    @Test
    fun test_bulk_approve_count_comes_from_counts_not_list_size() =
        runTest {
            // The queue is served whole today, but the count must come from
            // `counts` so a future paginated list cannot silently shrink it.
            val (viewModel, _) =
                loadedViewModel(
                    cards = listOf(pendingCard(id = 1), pendingCard(id = 2)),
                    counts = PendingCounts(pendingReview = 122, needsHuman = 4),
                )
            advanceUntilIdle()

            assertEquals(122, viewModel.uiState.value.bulkApprovableCount)
        }

    @Test
    fun test_bulk_approve_partial_failure_keeps_failed_cards_in_the_queue() =
        runTest {
            val (viewModel, repository) =
                loadedViewModel(listOf(pendingCard(id = 1), pendingCard(id = 2)))
            advanceUntilIdle()
            repository.batchResult = { ids ->
                Result.Success(
                    ids.map { ApproveBatchResult(cardId = it, ok = it != 2L, detail = null) },
                )
            }

            viewModel.approveAllClean()
            advanceUntilIdle()

            val remaining =
                viewModel.uiState.value.groups
                    .flatMap { group -> group.cards.map { it.id } }
            assertEquals(listOf(2L), remaining)
        }

    @Test
    fun test_flat_list_groups_by_book_then_chapter() =
        runTest {
            // The server orders by book, chapter, export_position; the client
            // groups runs of equal (book, chapter) without reordering. The
            // interleaved repeat of (book 1, ch 1) after book 2 is the case
            // that distinguishes run grouping from a re-sorting groupBy.
            val (viewModel, _) =
                loadedViewModel(
                    listOf(
                        pendingCard(id = 1, bookId = 1, chapter = "1. Introduction"),
                        pendingCard(id = 2, bookId = 1, chapter = "1. Introduction"),
                        pendingCard(id = 3, bookId = 1, chapter = "3. Error Analysis"),
                        pendingCard(id = 4, bookId = 2, book = "30 Agents", chapter = "1. Introduction"),
                        pendingCard(id = 5, bookId = 1, chapter = "1. Introduction"),
                    ),
                )
            advanceUntilIdle()

            val groups = viewModel.uiState.value.groups
            assertEquals(4, groups.size)
            assertEquals(Triple(1L, "Evals for AI Engineers", "1. Introduction"), groups[0].key())
            assertEquals(listOf(1L, 2L), groups[0].cards.map { it.id })
            assertEquals(Triple(1L, "Evals for AI Engineers", "3. Error Analysis"), groups[1].key())
            assertEquals(listOf(3L), groups[1].cards.map { it.id })
            assertEquals(Triple(2L, "30 Agents", "1. Introduction"), groups[2].key())
            assertEquals(listOf(4L), groups[2].cards.map { it.id })
            // The repeated run stays in place as its own group — order preserved.
            assertEquals(Triple(1L, "Evals for AI Engineers", "1. Introduction"), groups[3].key())
            assertEquals(listOf(5L), groups[3].cards.map { it.id })
        }

    @Test
    fun test_needs_human_card_is_badged() =
        runTest {
            val (viewModel, _) =
                loadedViewModel(
                    listOf(
                        pendingCard(id = 1, status = PendingCard.STATUS_PENDING_REVIEW),
                        pendingCard(id = 2, status = PendingCard.STATUS_NEEDS_HUMAN),
                    ),
                )
            advanceUntilIdle()

            val cards =
                viewModel.uiState.value.groups
                    .flatMap { it.cards }
            assertFalse(cards.single { it.id == 1L }.isNeedsHuman)
            assertTrue("a needs_human card carries the badge", cards.single { it.id == 2L }.isNeedsHuman)

            // Reachable by the "needs you" filter: only that card remains.
            viewModel.onFilterChange(QueueFilter.NEEDS_YOU)
            advanceUntilIdle()
            val filtered =
                viewModel.uiState.value.visibleGroups
                    .flatMap { it.cards }
            assertEquals(listOf(2L), filtered.map { it.id })

            viewModel.onFilterChange(QueueFilter.ALL)
            advanceUntilIdle()
            assertEquals(
                listOf(1L, 2L),
                viewModel.uiState.value.visibleGroups
                    .flatMap { it.cards }
                    .map { it.id },
            )
        }

    @Test
    fun test_no_bulk_approve_affordance() {
        // Bulk approve landed in #168: bulk approve exists, and takes no card ids from
        // the caller — the ViewModel derives the clean set itself, so no
        // screen can hand it a needs_human id (hard rule 1). This replaces the
        // pre-#168 guard that forbade a bulk path outright.
        val methods = ApproveViewModel::class.java.declaredMethods.filter { !it.isSynthetic }
        val bulk = methods.singleOrNull { it.name == "approveAllClean" }
        assertTrue("a single bulk-approve entry point exists: $methods", bulk != null)
        assertTrue(
            "the bulk entry point takes no arguments: ${bulk?.parameterTypes?.toList()}",
            bulk!!.parameterTypes.isEmpty(),
        )
        assertTrue(
            "no other method may take a collection of card ids: $methods",
            methods.filter { it.name != "approveAllClean" }.none { method ->
                method.parameterTypes.any { java.util.Collection::class.java.isAssignableFrom(it) }
            },
        )
        // The UiState carries data only — no callback fields at all, so there
        // is nowhere a multi-card approve affordance could hide.
        assertTrue(
            "ApproveUiState must not carry callbacks",
            ApproveUiState::class.java.declaredFields.none {
                !it.isSynthetic && !it.name.startsWith("$") && Function::class.java.isAssignableFrom(it.type)
            },
        )
    }

    @Test
    fun test_ui_state_carries_the_header_count_and_no_bulk_field() =
        runTest {
            // G1 (issue #132): the header count is a named field fed by the
            // response `counts` (both buckets — "8 pending" is the whole
            // queue). Since #168 the bulk count sits beside it and is fed by
            // the `pending_review` bucket alone — needs_human is never in the
            // bulk N (hard rule 1). (Synthetic fields — e.g. the Compose
            // compiler's `$stable` — are excluded.)
            val fields =
                ApproveUiState::class.java.declaredFields.filter { !it.isSynthetic && !it.name.startsWith("$") }
            val headerCount = fields.singleOrNull { it.name == "pendingCount" }
            assertTrue("ApproveUiState carries the header count as `pendingCount`: $fields", headerCount != null)
            val bulkCount = fields.singleOrNull { it.name == "bulkApprovableCount" }
            assertTrue("the bulk count is its own named field: $fields", bulkCount != null)

            val (viewModel, _) =
                loadedViewModel(
                    cards = listOf(pendingCard(id = 1)),
                    counts = PendingCounts(pendingReview = 5, needsHuman = 3),
                )
            advanceUntilIdle()
            assertEquals("the header count is the whole queue, not the list length", 8, viewModel.uiState.value.pendingCount)
            assertEquals(
                "the bulk count is the pending_review bucket only, never including needs_human",
                5,
                viewModel.uiState.value.bulkApprovableCount,
            )
        }

    @Test
    fun test_truncated_is_flagged_never_repaired() =
        runTest {
            val (viewModel, _) = loadedViewModel(listOf(pendingCard(id = 1, truncated = true)))
            advanceUntilIdle()

            val card =
                viewModel.uiState.value.groups
                    .flatMap { it.cards }
                    .single()
            assertTrue("the truncated flag must reach the UiState", card.truncated)

            // Hard rule 7: the clipped text exists nowhere, so nothing in the
            // public surface may offer to reconstruct it.
            val repairRegex = Regex("reconstruct|repair|restore|recover|rewrite", RegexOption.IGNORE_CASE)
            val viewModelMethods =
                ApproveViewModel::class.java.declaredMethods
                    .filter { !it.isSynthetic }
                    .map { it.name }
            val stateFields =
                ApproveUiState::class.java.declaredFields
                    .filter { !it.isSynthetic && !it.name.startsWith("$") }
                    .map { it.name }
            assertTrue("ViewModel offers no repair action: $viewModelMethods", viewModelMethods.none(repairRegex::containsMatchIn))
            assertTrue("UiState carries no repair affordance: $stateFields", stateFields.none(repairRegex::containsMatchIn))
        }

    @Test
    fun test_highlights_collapsed_by_default() =
        runTest {
            val (viewModel, _) =
                loadedViewModel(
                    listOf(pendingCard(id = 1, sourceHighlights = listOf("one", "two", "three"))),
                )
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertTrue("highlights start collapsed", state.expandedHighlightCardIds.isEmpty())
            assertFalse(state.isHighlightsExpanded(1L))

            // And expanding is an explicit toggle, per card.
            viewModel.onToggleHighlights(1L)
            advanceUntilIdle()
            assertTrue(viewModel.uiState.value.isHighlightsExpanded(1L))
        }

    @Test
    fun test_offline_disables_approve_reject_edit() =
        runTest {
            // The approval queue requires connectivity (docs/android.md,
            // "Offline-first sync"): offline, the screen is disabled with an
            // explanatory row and approve/reject/edit are never queued writes.
            val repository =
                FakeApprovalRepository(Result.NetworkError(IOException("no route to host")))
            val viewModel = viewModelWith(repository)
            advanceUntilIdle()

            val offlineState = viewModel.uiState.value
            assertTrue("a network failure on load is the offline state", offlineState.isOffline)
            assertFalse(offlineState.isLoading)

            viewModel.approveCard(1L)
            viewModel.editCard(1L, "edited front", "edited back")
            viewModel.rejectCard(1L, "trivia")
            advanceUntilIdle()

            assertTrue("approve must not fire offline", repository.approveCalls.isEmpty())
            assertTrue("save must not fire offline", repository.saveCalls.isEmpty())
            assertTrue("reject must not fire offline", repository.rejectCalls.isEmpty())
            assertEquals("no refetch either", 1, repository.pendingCalls)
            assertTrue(viewModel.uiState.value.isOffline)
        }

    @Test
    fun test_save_edit_keeps_card_in_queue_and_closes_editor() =
        runTest {
            // Issue #287: Save ≠ approve. A successful Save updates the card's
            // text, closes the inline editor and leaves the card queued.
            val (viewModel, repository) = loadedViewModel(listOf(pendingCard(id = 1)))
            advanceUntilIdle()
            viewModel.onStartEdit(1L)
            advanceUntilIdle()
            assertEquals(1L, viewModel.uiState.value.editingCardId)

            viewModel.editCard(1L, "edited front", "edited back")
            advanceUntilIdle()

            assertEquals(
                listOf(FakeApprovalRepository.SaveCall(1L, "edited front", "edited back")),
                repository.saveCalls,
            )
            val remaining =
                viewModel.uiState.value.groups
                    .flatMap { it.cards }
                    .map { it.id }
            assertEquals("Save keeps the card queued", listOf(1L), remaining)
            assertNull("Save closes the editor", viewModel.uiState.value.editingCardId)
            assertNull("no error on success", viewModel.uiState.value.errorMessage)
        }

    @Test
    fun test_save_edit_updates_text_without_changing_pending_counts() =
        runTest {
            // The saved front/back reach UiState in place; the collection-wide
            // counts (both buckets) are part of the decision-free contract.
            val (viewModel, _) =
                loadedViewModel(
                    listOf(pendingCard(id = 1)),
                    counts = PendingCounts(pendingReview = 5, needsHuman = 3),
                )
            advanceUntilIdle()

            viewModel.editCard(1L, "edited front", "edited back")
            advanceUntilIdle()

            val card =
                viewModel.uiState.value.groups
                    .flatMap { it.cards }
                    .single()
            assertEquals("edited front", card.front)
            assertEquals("edited back", card.back)
            assertEquals("the whole-queue count is unchanged", 8, viewModel.uiState.value.pendingCount)
            assertEquals(
                "the bulk-approvable count is unchanged",
                5,
                viewModel.uiState.value.bulkApprovableCount,
            )
        }

    @Test
    fun test_save_edit_never_calls_approve() =
        runTest {
            // Negative: the Save path calls PATCH/save only — never single or
            // bulk approve. A Save that approved would drop the card from the
            // queue, so the card's presence is asserted too.
            val (viewModel, repository) = loadedViewModel(listOf(pendingCard(id = 1)))
            advanceUntilIdle()

            viewModel.editCard(1L, "edited front", "edited back")
            advanceUntilIdle()

            assertTrue("the save went through the save path", repository.saveCalls.isNotEmpty())
            assertTrue(
                "Save must never call single approve (negative)",
                repository.approveCalls.isEmpty(),
            )
            assertTrue(
                "Save must never call bulk approve (negative)",
                repository.batchCalls.isEmpty(),
            )
            assertEquals(
                "the card is still queued",
                listOf(1L),
                viewModel.uiState.value.groups
                    .flatMap { it.cards }
                    .map { it.id },
            )
        }

    @Test
    fun test_save_failure_keeps_editor_open_and_card_unchanged() =
        runTest {
            // A failed Save does not discard input state or optimistically
            // rewrite the card: the editor stays open and the text is the
            // server's last truth.
            val (viewModel, repository) = loadedViewModel(listOf(pendingCard(id = 1)))
            advanceUntilIdle()
            repository.saveResult = Result.HttpError(status = 409, detail = "Card 1 is not editable.")
            viewModel.onStartEdit(1L)
            advanceUntilIdle()

            viewModel.editCard(1L, "edited front", "edited back")
            advanceUntilIdle()

            assertEquals("the editor stays open", 1L, viewModel.uiState.value.editingCardId)
            val card =
                viewModel.uiState.value.groups
                    .flatMap { it.cards }
                    .single()
            assertEquals("the card text is not rewritten", "Front of card 1", card.front)
            assertEquals("Back of card 1", card.back)
            assertTrue("the failure surfaces", viewModel.uiState.value.errorMessage != null)
        }

    @Test
    fun test_approve_after_saved_edit_sends_no_edit_payload() =
        runTest {
            // The later Approve is a bodyless decision over the saved text:
            // the repository's approve carries no front/back at all (the
            // server's optional approve-with-edits payload stays a server-side
            // compatibility contract, not a UI action — issue #287).
            val (viewModel, repository) = loadedViewModel(listOf(pendingCard(id = 1)))
            advanceUntilIdle()

            viewModel.editCard(1L, "edited front", "edited back")
            advanceUntilIdle()
            viewModel.approveCard(1L)
            advanceUntilIdle()

            assertEquals("exactly one save, then one bodyless approve", 1, repository.saveCalls.size)
            assertEquals(listOf(1L), repository.approveCalls)
            assertTrue(
                "approve drops the card after the saved edit",
                viewModel.uiState.value.groups
                    .flatMap { it.cards }
                    .isEmpty(),
            )
        }

    @Test
    fun test_offline_save_is_not_queued() =
        runTest {
            // The approval queue requires connectivity (docs/android.md,
            // "Offline-first sync"): offline, Save performs no repository
            // write. The ratings outbox is untouched structurally — this
            // ViewModel has no outbox dependency at all; ratings are the only
            // queued write.
            val repository =
                FakeApprovalRepository(Result.NetworkError(IOException("no route to host")))
            val viewModel = viewModelWith(repository)
            advanceUntilIdle()
            assertTrue(viewModel.uiState.value.isOffline)

            viewModel.editCard(1L, "edited front", "edited back")
            advanceUntilIdle()

            assertTrue("offline Save performs no repository write", repository.saveCalls.isEmpty())
            assertTrue(repository.approveCalls.isEmpty())
            assertTrue(repository.batchCalls.isEmpty())
            assertEquals("no refetch either", 1, repository.pendingCalls)
            assertTrue(viewModel.uiState.value.isOffline)
        }

    @Test
    fun test_approving_removes_the_card_from_the_queue() =
        runTest {
            val (viewModel, repository) =
                loadedViewModel(listOf(pendingCard(id = 1), pendingCard(id = 2)))
            advanceUntilIdle()

            viewModel.approveCard(1L)
            advanceUntilIdle()

            assertEquals(listOf(1L), repository.approveCalls)
            val remaining =
                viewModel.uiState.value.groups
                    .flatMap { it.cards }
            assertEquals(
                "a successful approve drops the card without a refetch",
                listOf(2L),
                remaining.map { it.id },
            )
            assertEquals("no refetch on approve", 1, repository.pendingCalls)
        }

    @Test
    fun test_reject_reason_is_optional() =
        runTest {
            val (viewModel, repository) = loadedViewModel(listOf(pendingCard(id = 1)))
            advanceUntilIdle()

            viewModel.rejectCard(1L)
            advanceUntilIdle()

            val call = repository.rejectCalls.single()
            assertEquals(1L, call.cardId)
            assertNull("a reject with no reason must reach the repository as null", call.reason)
            assertTrue(
                "a successful reject drops the card",
                viewModel.uiState.value.groups
                    .flatMap { it.cards }
                    .isEmpty(),
            )
        }

    // --- Issue #343: reject-reason drafts are auto-saved per card ---

    @Test
    fun test_saved_drafts_are_loaded_before_the_queue_shows() =
        runTest {
            // A draft from a previous run must be in ApproveUiState by the
            // time the queue fetch runs, so a dialog can never open empty and
            // then be overwritten. The hook captures the UiState at the
            // pendingCards() call — the ordering proof, not just the settled
            // end state.
            draftsRepository.drafts[1L] = "too trivial"
            var draftsInStateAtQueueLoad: Map<Long, String>? = null
            lateinit var viewModel: ApproveViewModel
            val repository =
                FakeApprovalRepository(
                    Result.Success(PendingQueue(listOf(pendingCard(id = 1)), PendingCounts(1, 0))),
                )
            repository.onPendingCards = {
                draftsInStateAtQueueLoad = viewModel.uiState.value.rejectReasonDrafts
            }
            viewModel = viewModelWith(repository)
            advanceUntilIdle()

            assertEquals(
                "the draft from a previous run is in ApproveUiState by the time the queue is loaded",
                mapOf(1L to "too trivial"),
                draftsInStateAtQueueLoad,
            )
            assertEquals(
                "the queue loaded too",
                listOf(1L),
                viewModel.uiState.value.groups
                    .flatMap { it.cards }
                    .map { it.id },
            )
            assertEquals(1, draftsRepository.loadCount)
        }

    @Test
    fun test_reason_change_is_saved_in_typing_order() =
        runTest {
            val (viewModel, _) = loadedViewModel(listOf(pendingCard(id = 1)))
            advanceUntilIdle()
            draftsRepository.writeLog.clear()

            // Several quick changes with no settle between them: the writes
            // must be applied in the order they were typed, and the last
            // keystroke is what stays stored.
            viewModel.onRejectReasonChange(1L, "t")
            viewModel.onRejectReasonChange(1L, "to")
            viewModel.onRejectReasonChange(1L, "too")
            advanceUntilIdle()

            assertEquals(
                "writes are applied in typing order",
                listOf(1L to "t", 1L to "to", 1L to "too"),
                draftsRepository.writeLog,
            )
            assertEquals("the last text is what stays stored", "too", draftsRepository.drafts[1L])
        }

    @Test
    fun test_blank_reason_removes_the_draft() =
        runTest {
            val (viewModel, _) = loadedViewModel(listOf(pendingCard(id = 1)))
            advanceUntilIdle()

            viewModel.onRejectReasonChange(1L, "too trivial")
            advanceUntilIdle()
            viewModel.onRejectReasonChange(1L, "")
            advanceUntilIdle()

            assertTrue("a blank reason removes the stored draft", draftsRepository.drafts.isEmpty())
            assertEquals(
                "and the UiState map",
                emptyMap<Long, String>(),
                viewModel.uiState.value.rejectReasonDrafts,
            )
        }

    @Test
    fun test_successful_reject_removes_the_draft() =
        runTest {
            val (viewModel, repository) = loadedViewModel(listOf(pendingCard(id = 1)))
            advanceUntilIdle()
            viewModel.onRejectReasonChange(1L, "too trivial")
            advanceUntilIdle()

            viewModel.rejectCard(1L, "too trivial")
            advanceUntilIdle()

            assertEquals(
                listOf(FakeApprovalRepository.RejectCall(1L, "too trivial")),
                repository.rejectCalls,
            )
            assertTrue("a successful reject removes the draft", draftsRepository.drafts.isEmpty())
            assertEquals(
                emptyMap<Long, String>(),
                viewModel.uiState.value.rejectReasonDrafts,
            )
        }

    @Test
    fun test_failed_reject_does_not_remove_the_draft() =
        runTest {
            val (viewModel, repository) = loadedViewModel(listOf(pendingCard(id = 1)))
            advanceUntilIdle()
            repository.rejectResult = Result.HttpError(status = 500, detail = "boom")
            viewModel.onRejectReasonChange(1L, "too trivial")
            advanceUntilIdle()

            viewModel.rejectCard(1L, "too trivial")
            advanceUntilIdle()

            assertEquals(
                "a failed reject keeps the draft so the reason is not lost (negative)",
                mapOf(1L to "too trivial"),
                draftsRepository.drafts,
            )
            assertEquals(
                mapOf(1L to "too trivial"),
                viewModel.uiState.value.rejectReasonDrafts,
            )
        }

    @Test
    fun test_successful_approve_removes_the_draft() =
        runTest {
            val (viewModel, _) = loadedViewModel(listOf(pendingCard(id = 1)))
            advanceUntilIdle()
            viewModel.onRejectReasonChange(1L, "too trivial")
            advanceUntilIdle()

            viewModel.approveCard(1L)
            advanceUntilIdle()

            assertTrue("a successful approve removes the draft", draftsRepository.drafts.isEmpty())
            assertEquals(
                emptyMap<Long, String>(),
                viewModel.uiState.value.rejectReasonDrafts,
            )
        }

    @Test
    fun test_failed_approve_does_not_remove_the_draft() =
        runTest {
            val (viewModel, repository) = loadedViewModel(listOf(pendingCard(id = 1)))
            advanceUntilIdle()
            repository.approveResult = Result.HttpError(status = 500, detail = "boom")
            viewModel.onRejectReasonChange(1L, "too trivial")
            advanceUntilIdle()

            viewModel.approveCard(1L)
            advanceUntilIdle()

            assertEquals(
                "a failed approve keeps the draft (negative)",
                mapOf(1L to "too trivial"),
                draftsRepository.drafts,
            )
        }

    @Test
    fun test_bulk_approve_removes_drafts_only_for_approved_cards() =
        runTest {
            val (viewModel, repository) =
                loadedViewModel(listOf(pendingCard(id = 1), pendingCard(id = 2)))
            advanceUntilIdle()
            viewModel.onRejectReasonChange(1L, "one")
            viewModel.onRejectReasonChange(2L, "two")
            advanceUntilIdle()
            repository.batchResult = { ids ->
                Result.Success(
                    ids.map { ApproveBatchResult(cardId = it, ok = it != 2L, detail = null) },
                )
            }

            viewModel.approveAllClean()
            advanceUntilIdle()

            assertEquals(
                "only the cards that came back ok lose their drafts (negative)",
                mapOf(2L to "two"),
                draftsRepository.drafts,
            )
            assertEquals(
                mapOf(2L to "two"),
                viewModel.uiState.value.rejectReasonDrafts,
            )
        }

    @Test
    fun test_reason_change_never_calls_reject() =
        runTest {
            // Negative: typing a reason is a local draft write — it must never
            // reach the approval repository as a reject (or any other call).
            val (viewModel, repository) = loadedViewModel(listOf(pendingCard(id = 1)))
            advanceUntilIdle()

            viewModel.onRejectReasonChange(1L, "too trivial")
            viewModel.onRejectReasonChange(1L, "too trivial, and vague")
            advanceUntilIdle()

            assertTrue("typing must never reject (negative)", repository.rejectCalls.isEmpty())
            assertTrue("typing must never approve (negative)", repository.approveCalls.isEmpty())
            assertTrue("typing must never bulk-approve (negative)", repository.batchCalls.isEmpty())
        }

    private fun ChapterGroup.key(): Triple<Long, String, String> = Triple(bookId, book, chapter)

    // --- Dropped units (issue #365) ---------------------------------------------

    private fun droppedViewModel(
        units: List<DroppedUnit>,
        count: Int = units.size,
        cards: List<PendingCard> = emptyList(),
    ): Pair<ApproveViewModel, FakeApprovalRepository> {
        val (viewModel, repository) = loadedViewModel(cards)
        repository.droppedResult = Result.Success(DroppedUnits(units, count))
        return viewModel to repository
    }

    private fun ApproveUiState.droppedIds(): List<Long> = droppedGroups.flatMap { group -> group.units.map { it.id } }

    private fun ApproveUiState.cardIds(): List<Long> = groups.flatMap { group -> group.cards.map { it.id } }

    @Test
    fun test_dropped_list_loads_with_queue_and_on_refresh() =
        runTest {
            val (viewModel, repository) = droppedViewModel(listOf(droppedUnit(id = 7), droppedUnit(id = 8)))
            advanceUntilIdle()

            assertEquals("the drops load on screen entry, with the queue", 1, repository.droppedCalls)
            assertEquals(1, repository.pendingCalls)
            assertEquals(listOf(7L, 8L), viewModel.uiState.value.droppedIds())

            repository.droppedResult = Result.Success(DroppedUnits(listOf(droppedUnit(id = 9)), 1))
            viewModel.refresh()
            advanceUntilIdle()

            assertEquals("refresh reloads the drops", 2, repository.droppedCalls)
            assertEquals(2, repository.pendingCalls)
            assertEquals(listOf(9L), viewModel.uiState.value.droppedIds())
        }

    @Test
    fun test_dropped_header_count_comes_from_server_count_not_list_length() =
        runTest {
            val (viewModel, _) = droppedViewModel(listOf(droppedUnit(id = 7)), count = 19)
            advanceUntilIdle()

            assertEquals(19, viewModel.uiState.value.droppedCount)
        }

    @Test
    fun test_dismiss_success_removes_item() =
        runTest {
            val (viewModel, repository) = droppedViewModel(listOf(droppedUnit(id = 7), droppedUnit(id = 8)))
            advanceUntilIdle()

            viewModel.dismissDrop(7)
            advanceUntilIdle()

            assertEquals(listOf(7L), repository.dismissCalls)
            assertEquals(listOf(8L), viewModel.uiState.value.droppedIds())
            assertEquals(1, viewModel.uiState.value.droppedCount)
            assertNull(viewModel.uiState.value.busyDropUnitId)
        }

    @Test
    fun test_dismiss_failure_keeps_item_and_shows_error() =
        runTest {
            val (viewModel, repository) = droppedViewModel(listOf(droppedUnit(id = 7)))
            advanceUntilIdle()
            repository.dismissResult = Result.HttpError(status = 409, detail = "Unit 7 is already resolved (dismissed).")

            viewModel.dismissDrop(7)
            advanceUntilIdle()

            assertEquals(listOf(7L), repository.dismissCalls)
            assertEquals("a failed dismiss keeps the item (negative)", listOf(7L), viewModel.uiState.value.droppedIds())
            assertEquals(1, viewModel.uiState.value.droppedCount)
            assertEquals("Unit 7 is already resolved (dismissed).", viewModel.uiState.value.dropErrors[7L])
            assertNull(viewModel.uiState.value.busyDropUnitId)
        }

    @Test
    fun test_generate_success_moves_returned_cards_into_queue() =
        runTest {
            val existing = pendingCard(id = 1, chapter = "1. Introduction")
            val (viewModel, repository) =
                droppedViewModel(
                    units = listOf(droppedUnit(id = 7, chapter = "2. Error Analysis"), droppedUnit(id = 8)),
                    cards = listOf(existing),
                )
            advanceUntilIdle()
            val generated =
                listOf(
                    pendingCard(id = 40, chapter = "2. Error Analysis"),
                    pendingCard(id = 41, chapter = "2. Error Analysis", status = PendingCard.STATUS_NEEDS_HUMAN),
                )
            repository.generateResult = Result.Success(generated)

            viewModel.generateDrop(7)
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertEquals(listOf(7L), repository.generateCalls)
            assertEquals("the item leaves Dropped", listOf(8L), state.droppedIds())
            assertEquals(1, state.droppedCount)
            assertEquals(
                "the cards land in their own chapter group, after the existing one",
                listOf("1. Introduction" to listOf(1L), "2. Error Analysis" to listOf(40L, 41L)),
                state.groups.map { group -> group.chapter to group.cards.map { it.id } },
            )
            assertEquals("no refetch: the cards came from the response", 1, repository.pendingCalls)
            assertEquals(3, state.pendingCount)
            assertEquals("only the pending_review card is bulk-approvable", 2, state.bulkApprovableCount)
            assertNull(state.busyDropUnitId)
            assertNull(state.generatingUnitId)
        }

    @Test
    fun test_generate_in_flight_marks_item_busy_and_blocks_second_tap() =
        runTest {
            val (viewModel, repository) = droppedViewModel(listOf(droppedUnit(id = 7), droppedUnit(id = 8)))
            advanceUntilIdle()
            val gate = CompletableDeferred<Unit>()
            repository.generateGate = gate
            repository.generateResult = Result.Success(listOf(pendingCard(id = 40)))

            viewModel.generateDrop(7)
            advanceUntilIdle()

            assertEquals(7L, viewModel.uiState.value.generatingUnitId)
            assertEquals(7L, viewModel.uiState.value.busyDropUnitId)

            viewModel.generateDrop(7)
            viewModel.generateDrop(8)
            viewModel.dismissDrop(7)
            viewModel.onStartManualCard(7)
            advanceUntilIdle()

            assertEquals("a second Generate makes no second call (negative)", listOf(7L), repository.generateCalls)
            assertTrue("no other Dropped action runs meanwhile (negative)", repository.dismissCalls.isEmpty())
            assertNull("the editor cannot open on the busy item (negative)", viewModel.uiState.value.manualCardDraft)

            gate.complete(Unit)
            advanceUntilIdle()

            assertNull(viewModel.uiState.value.generatingUnitId)
            assertEquals(listOf(8L), viewModel.uiState.value.droppedIds())
        }

    @Test
    fun test_generate_failure_keeps_item() =
        runTest {
            val (viewModel, repository) = droppedViewModel(listOf(droppedUnit(id = 7)))
            advanceUntilIdle()
            repository.generateResult =
                Result.HttpError(status = 502, detail = "Card generation failed (RuntimeError); the highlight is still in Dropped.")

            viewModel.generateDrop(7)
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertEquals(listOf(7L), repository.generateCalls)
            assertEquals("a failed Generate keeps the item (negative)", listOf(7L), state.droppedIds())
            assertTrue("no card enters the queue (negative)", state.groups.isEmpty())
            assertEquals(
                "Card generation failed (RuntimeError); the highlight is still in Dropped.",
                state.dropErrors[7L],
            )
            assertNull(state.generatingUnitId)
            assertNull(state.busyDropUnitId)
        }

    @Test
    fun test_manual_card_success_moves_card_into_queue_as_pending_review() =
        runTest {
            val (viewModel, repository) = droppedViewModel(listOf(droppedUnit(id = 7, chapter = "2. Error Analysis")))
            advanceUntilIdle()
            repository.manualResult =
                Result.Success(pendingCard(id = 50, chapter = "2. Error Analysis", status = PendingCard.STATUS_PENDING_REVIEW))

            viewModel.onStartManualCard(7)
            viewModel.onManualCardChange(
                ManualCardDraft(unitId = 7, type = PendingCard.TYPE_QA, front = "What is error analysis?", back = "Reading traces."),
            )
            viewModel.submitManualCard()
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertEquals(
                listOf(FakeApprovalRepository.ManualCall(7, PendingCard.TYPE_QA, "What is error analysis?", "Reading traces.")),
                repository.manualCalls,
            )
            assertTrue("the item leaves Dropped", state.droppedIds().isEmpty())
            assertEquals(0, state.droppedCount)
            assertEquals(listOf(50L), state.cardIds())
            assertEquals(
                PendingCard.STATUS_PENDING_REVIEW,
                state.groups
                    .single()
                    .cards
                    .single()
                    .status,
            )
            assertEquals(1, state.pendingCount)
            assertEquals(1, state.bulkApprovableCount)
            assertNull("the editor closes", state.manualCardDraft)
            assertTrue("writing a card never approves it (hard rule 1)", repository.approveCalls.isEmpty())
        }

    @Test
    fun test_manual_card_422_keeps_typed_text_and_shows_detail() =
        runTest {
            val (viewModel, repository) = droppedViewModel(listOf(droppedUnit(id = 7)))
            advanceUntilIdle()
            val detail = "Value error, a cloze front needs exactly one {{c1::…}} deletion"
            repository.manualResult = Result.HttpError(status = 422, detail = detail)
            val typed =
                ManualCardDraft(unitId = 7, type = PendingCard.TYPE_CLOZE, front = "Two {{c1::blanks}} {{c2::here}}", back = "")

            viewModel.onStartManualCard(7)
            viewModel.onManualCardChange(typed)
            viewModel.submitManualCard()
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertEquals(1, repository.manualCalls.size)
            assertEquals("the typed text survives the 422 (negative)", typed.copy(error = detail), state.manualCardDraft)
            assertEquals("the item stays listed (negative)", listOf(7L), state.droppedIds())
            assertTrue("no card enters the queue (negative)", state.groups.isEmpty())
        }

    private companion object {
        fun droppedUnit(
            id: Long,
            bookId: Long = 1,
            book: String = "Evals for AI Engineers",
            chapter: String = "1. Introduction",
            truncated: Boolean = false,
            sourceHighlights: List<String> = listOf("A dropped highlight $id."),
            reason: String? = "Narrative framing with no retrievable claim.",
        ): DroppedUnit =
            DroppedUnit(
                id = id,
                reason = reason,
                curatedText = sourceHighlights.joinToString(" "),
                sourceHighlights = sourceHighlights,
                truncated = truncated,
                bookId = bookId,
                book = book,
                chapter = chapter,
            )

        fun pendingCard(
            id: Long,
            bookId: Long = 1,
            book: String = "Evals for AI Engineers",
            chapter: String = "1. Introduction",
            status: String = PendingCard.STATUS_PENDING_REVIEW,
            truncated: Boolean = false,
            sourceHighlights: List<String> = listOf("A source highlight."),
            statusReason: String? = null,
        ): PendingCard =
            PendingCard(
                id = id,
                status = status,
                type = PendingCard.TYPE_QA,
                front = "Front of card $id",
                back = "Back of card $id",
                statusReason = statusReason,
                sourceHighlights = sourceHighlights,
                truncated = truncated,
                bookId = bookId,
                book = book,
                chapter = chapter,
            )
    }
}
