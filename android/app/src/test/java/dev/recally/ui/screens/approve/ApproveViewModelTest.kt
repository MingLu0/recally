package dev.recally.ui.screens.approve

import dev.recally.domain.model.ApproveBatchResult
import dev.recally.domain.model.PendingCard
import dev.recally.domain.model.PendingCounts
import dev.recally.domain.model.PendingQueue
import dev.recally.domain.repository.ApprovalRepository
import dev.recally.domain.repository.Result
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
        val approveCalls = mutableListOf<Long>()
        val editCalls = mutableListOf<Long>()

        data class RejectCall(
            val cardId: Long,
            val reason: String?,
        )

        val rejectCalls = mutableListOf<RejectCall>()

        override suspend fun pendingCards(): Result<PendingQueue> {
            pendingCalls++
            return pendingResult
        }

        override suspend fun approveCard(
            cardId: Long,
            front: String?,
            back: String?,
        ): Result<Unit> {
            if (front != null || back != null) {
                editCalls += cardId
            } else {
                approveCalls += cardId
            }
            return Result.Success(Unit)
        }

        override suspend fun rejectCard(
            cardId: Long,
            reason: String?,
        ): Result<Unit> {
            rejectCalls += RejectCall(cardId, reason)
            return Result.Success(Unit)
        }

        val batchCalls = mutableListOf<List<Long>>()
        var batchResult: (List<Long>) -> Result<List<ApproveBatchResult>> = { ids ->
            Result.Success(ids.map { ApproveBatchResult(cardId = it, ok = true) })
        }

        override suspend fun approveBatch(cardIds: List<Long>): Result<List<ApproveBatchResult>> {
            batchCalls += cardIds
            return batchResult(cardIds)
        }
    }

    private fun viewModelWith(repository: FakeApprovalRepository): ApproveViewModel =
        ApproveViewModel(
            approvalRepository = repository,
            ioDispatcher = testDispatcher,
        )

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
            assertTrue("edit must not fire offline", repository.editCalls.isEmpty())
            assertTrue("reject must not fire offline", repository.rejectCalls.isEmpty())
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

    private fun ChapterGroup.key(): Triple<Long, String, String> = Triple(bookId, book, chapter)

    private companion object {
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
