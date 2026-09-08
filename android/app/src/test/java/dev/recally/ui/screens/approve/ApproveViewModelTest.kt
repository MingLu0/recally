package dev.recally.ui.screens.approve

import dev.recally.domain.model.PendingCard
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
        var pendingResult: Result<List<PendingCard>>,
    ) : ApprovalRepository {
        var pendingCalls = 0
        val approveCalls = mutableListOf<Long>()
        val editCalls = mutableListOf<Long>()

        data class RejectCall(
            val cardId: Long,
            val reason: String?,
        )

        val rejectCalls = mutableListOf<RejectCall>()

        override suspend fun pendingCards(): Result<List<PendingCard>> {
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
    }

    private fun viewModelWith(repository: FakeApprovalRepository): ApproveViewModel =
        ApproveViewModel(
            approvalRepository = repository,
            ioDispatcher = testDispatcher,
        )

    private fun loadedViewModel(cards: List<PendingCard>): Pair<ApproveViewModel, FakeApprovalRepository> {
        val repository = FakeApprovalRepository(Result.Success(cards))
        return viewModelWith(repository) to repository
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
        // G4 is scoped out: cards are approved individually, so no public
        // ViewModel method may take a collection of card ids or read as a
        // bulk action. (Synthetic default-arg methods are excluded.)
        val methods = ApproveViewModel::class.java.declaredMethods.filter { !it.isSynthetic }
        assertTrue(
            "no method may take a collection of card ids: $methods",
            methods.none { method -> method.parameterTypes.any { java.util.Collection::class.java.isAssignableFrom(it) } },
        )
        assertTrue(
            "no bulk/approve-all/approve-ready method: $methods",
            methods.none { Regex("bulk|approveall|approveready", RegexOption.IGNORE_CASE).containsMatchIn(it.name) },
        )
        // The UiState carries data only — no callback fields at all, so there
        // is nowhere a multi-card approve affordance could hide.
        assertTrue(
            "ApproveUiState must not carry callbacks",
            ApproveUiState::class.java.declaredFields.none {
                kotlin.jvm.functions.Function::class.java.isAssignableFrom(it.type)
            },
        )
    }

    @Test
    fun test_ui_state_has_no_pending_count() {
        // G1 is scoped out: GET /cards/pending returns no counts, so the
        // header count is not built and the UiState carries no Int count field.
        assertTrue(
            "ApproveUiState must have no Int field",
            ApproveUiState::class.java.declaredFields.none { it.type == Int::class.javaPrimitiveType },
        )
        assertTrue(
            "ApproveUiState must have no count/pending-named field",
            ApproveUiState::class.java.declaredFields.none {
                Regex("count|pending", RegexOption.IGNORE_CASE).containsMatchIn(it.name)
            },
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
            val stateFields = ApproveUiState::class.java.declaredFields.map { it.name }
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
