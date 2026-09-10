package dev.recally.ui.screens.decks

import androidx.lifecycle.SavedStateHandle
import dev.recally.domain.model.Deck
import dev.recally.domain.model.DeckCard
import dev.recally.domain.model.DueSummary
import dev.recally.domain.repository.CardRepository
import dev.recally.domain.repository.DeckRepository
import dev.recally.domain.repository.Result
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
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
import java.time.Instant

/**
 * ViewModel tests against fake repositories on a TestDispatcher
 * (docs/android.md, "Tests"). These are the step-4j gate (ADR-012): the
 * browse list shows suspended cards, the ADR-008 controls never touch
 * scheduling, chapter filtering is a server filter, the screen is read-only
 * for review actions, and no G2/G5/G6 field is invented.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DecksViewModelTest {
    private lateinit var deckRepository: FakeDeckRepository
    private lateinit var cardRepository: FakeCardRepository

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        deckRepository = FakeDeckRepository()
        cardRepository = FakeCardRepository()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun test_suspended_cards_are_visible_in_browse() =
        runTest {
            val suspendedCard = browseCard(id = 55, suspendedUntil = FAR_FUTURE)
            deckRepository.deckCardsResult = Result.Success(listOf(suspendedCard, browseCard(id = 56)))
            val viewModel = detailViewModel()

            viewModel.toggleChapter(CHAPTER)

            val renderedCards = viewModel.uiState.value.expandedCards
            val rendered = renderedCards.firstOrNull { it.id == 55L }
            assertTrue("suspended card must stay in the browse list", rendered != null)
            assertTrue("suspended card must read as suspended", rendered!!.isSuspended)
        }

    @Test
    fun test_unsuspend_restores_the_card() =
        runTest {
            val served = browseCard(id = 55, suspendedUntil = FAR_FUTURE)
            deckRepository.deckCardsResult = Result.Success(listOf(served))
            val viewModel = detailViewModel()
            viewModel.toggleChapter(CHAPTER)
            val fetchesBeforeUnsuspend = deckRepository.deckCardsCalls.size

            viewModel.unsuspendCard(55)

            assertEquals(listOf(55L), cardRepository.unsuspendCalls)
            val restored =
                viewModel.uiState.value.expandedCards
                    .first { it.id == 55L }
            assertNull("unsuspend clears suspended_until", restored.suspendedUntil)
            assertFalse("card reads as in rotation", restored.isSuspended)
            // No client-side recomputation: the restore is the server's null
            // written into the row, not a refetch and not a new due date.
            assertEquals(fetchesBeforeUnsuspend, deckRepository.deckCardsCalls.size)
            assertSchedulingUntouched(viewModel, 55L, served)
        }

    @Test
    fun test_edit_leaves_scheduling_untouched() =
        runTest {
            val served = browseCard(id = 55, suspendedUntil = FAR_FUTURE)
            deckRepository.deckCardsResult = Result.Success(listOf(served))
            val viewModel = detailViewModel()
            viewModel.toggleChapter(CHAPTER)
            val fetchesBeforeEdit = deckRepository.deckCardsCalls.size

            viewModel.startEdit(
                viewModel.uiState.value.expandedCards
                    .first { it.id == 55L },
            )
            viewModel.submitEdit(front = "edited front", back = "edited back")

            assertEquals(listOf(EditCall(55L, "edited front", "edited back", null)), cardRepository.editCalls)
            val edited =
                viewModel.uiState.value.expandedCards
                    .first { it.id == 55L }
            assertEquals("edited front", edited.front)
            assertEquals("edited back", edited.back)
            assertEquals("suspend state is not an edit's business", FAR_FUTURE, edited.suspendedUntil)
            // ADR-008: an edit carries no rescheduling — no refetch, and the
            // server's state/due survive the edit byte for byte.
            assertEquals(fetchesBeforeEdit, deckRepository.deckCardsCalls.size)
            assertSchedulingUntouched(viewModel, 55L, served)
        }

    @Test
    fun test_chapter_filter_requests_the_server_filter() =
        runTest {
            deckRepository.deckCardsResult =
                Result.Success(listOf(browseCard(id = 55), browseCard(id = 56, chapter = OTHER_CHAPTER)))
            val viewModel = detailViewModel()

            // With the filter active the response is the server's filtered
            // set — here a disjoint card the unfiltered fetch never returned.
            deckRepository.deckCardsResult = Result.Success(listOf(browseCard(id = 90, chapter = OTHER_CHAPTER)))
            viewModel.toggleChapter(OTHER_CHAPTER)

            assertEquals(
                listOf(BOOK_ID to null, BOOK_ID to OTHER_CHAPTER),
                deckRepository.deckCardsCalls,
            )
            assertEquals(
                listOf(90L),
                viewModel.uiState.value.expandedCards
                    .map { it.id },
            )
        }

    @Test
    fun test_cards_are_read_only_here() {
        val reviewAction = Regex("rate|rating|approve", RegexOption.IGNORE_CASE)
        // Meta-assertion: the check itself bites, so it cannot pass vacuously.
        assertTrue(reviewAction.containsMatchIn("rateCard"))
        assertTrue(reviewAction.containsMatchIn("approveCard"))

        val viewModelActions =
            DecksViewModel::class.java.declaredMethods
                .map { it.name }
                .filter { reviewAction.containsMatchIn(it) }
        val stateFields =
            DecksUiState::class.java.declaredFields
                .map { it.name }
                .filter { reviewAction.containsMatchIn(it) }
        assertTrue("no rate action on the ViewModel: $viewModelActions", viewModelActions.isEmpty())
        assertTrue("no rate/approve field on the UiState: $stateFields", stateFields.isEmpty())
    }

    @Test
    fun test_offline_disables_edit_suspend_unsuspend() =
        runTest {
            deckRepository.deckCardsResult = Result.Success(listOf(browseCard(id = 55)))
            val viewModel = detailViewModel()
            viewModel.toggleChapter(CHAPTER)
            viewModel.startEdit(
                viewModel.uiState.value.expandedCards
                    .first { it.id == 55L },
            )

            // The connection drops; a refresh fails but the loaded list stays.
            val offline = Result.NetworkError(IOException("unreachable"))
            deckRepository.deckCardsResult = offline
            deckRepository.decksResult = offline
            viewModel.refresh()

            val offlineState = viewModel.uiState.value
            assertTrue(offlineState.isOffline)
            assertFalse("all three controls are disabled offline", offlineState.cardControlsEnabled)
            assertEquals("the loaded list is still browsable", listOf(55L), offlineState.expandedCards.map { it.id })

            viewModel.submitEdit(front = "x", back = "y")
            viewModel.suspendCard(55)
            viewModel.unsuspendCard(55)

            // Disabled, not queued (docs/android.md, "Offline-first sync").
            assertTrue(cardRepository.editCalls.isEmpty())
            assertTrue(cardRepository.suspendCalls.isEmpty())
            assertTrue(cardRepository.unsuspendCalls.isEmpty())
        }

    @Test
    fun test_ui_state_carries_a_server_sourced_truncated_count() =
        runTest {
            // Issue #173 resolved G6 by building the count rather than dropping the
            // badge, so this replaces the #133-era guard that forbade the field. The
            // property it now protects is the same one: nothing about truncation is
            // computed on the phone. `truncated` is a `GET /decks` field, so the only
            // legitimate source is the repository's `Deck`.
            val truncatedField = Regex("truncated", RegexOption.IGNORE_CASE)
            // Meta-assertion: the check bites on the shape it is looking for.
            assertTrue(truncatedField.containsMatchIn("truncatedCount"))

            // Deck is where the server's count lands, alongside the G2 progress field.
            val deckFields = Deck::class.java.declaredFields.map { it.name }
            assertTrue("Deck must carry the G2 progress field", deckFields.contains("progress"))
            assertTrue(
                "Deck must carry the G6 truncated count: $deckFields",
                deckFields.any { truncatedField.containsMatchIn(it) },
            )

            // Nothing downstream of Deck invents or recomputes one: the per-card
            // browse rows and the client-side chapter grouping carry no truncation,
            // and DecksUiState holds no derived total of its own.
            for (type in listOf(DecksUiState::class, DeckCard::class, ChapterSummary::class)) {
                val offending =
                    type.java.declaredFields
                        .map { it.name }
                        .filter { truncatedField.containsMatchIn(it) }
                assertTrue(
                    "${type.simpleName} must not derive a truncated count: $offending",
                    offending.isEmpty(),
                )
            }

            // Server-sourced end to end: a value only the fake repository could have
            // produced arrives in the UiState unchanged.
            deckRepository.decksResult =
                Result.Success(
                    listOf(Deck(BOOK_ID, "30 Agents in 30 Days", 83, 6, 0.24f, chapters = 30, truncated = 7)),
                )
            val viewModel = DecksViewModel(deckRepository, cardRepository, SavedStateHandle())

            assertEquals(
                7,
                viewModel.uiState.value.decks
                    .single()
                    .truncated,
            )
        }

    @Test
    fun test_refresh_re_queries_decks() =
        runTest {
            // Issue #147: refresh() on the surviving ViewModel re-queries the
            // deck list rather than serving the first load forever.
            deckRepository.decksResult =
                Result.Success(listOf(Deck(BOOK_ID, "Evals for AI Engineers", 48, 6, 0.625f, chapters = 9, truncated = 0)))
            val viewModel = DecksViewModel(deckRepository, cardRepository, SavedStateHandle())
            assertEquals(
                listOf("Evals for AI Engineers"),
                viewModel.uiState.value.decks
                    .map { it.title },
            )

            deckRepository.decksResult =
                Result.Success(
                    listOf(
                        Deck(BOOK_ID, "Evals for AI Engineers", 48, 6, 0.625f, chapters = 9, truncated = 0),
                        Deck(9L, "Designing Machine Learning Systems", 100, 10, 0.5f, chapters = 12, truncated = 0),
                    ),
                )
            viewModel.refresh()

            assertEquals("a second refresh issues a new /decks call", 2, deckRepository.decksCalls)
            assertEquals(
                "the updated list is emitted",
                listOf("Evals for AI Engineers", "Designing Machine Learning Systems"),
                viewModel.uiState.value.decks
                    .map { it.title },
            )
        }

    /**
     * Issue #188: the offline bar is a statement about connectivity, so only a
     * genuine `NetworkError` may raise it. An unexpected exception on a
     * reachable server (a decoding fault, say) arrives as `UnexpectedError` and
     * must surface as an error message, never as "offline".
     */
    @Test
    fun test_offline_is_set_only_for_a_network_error() =
        runTest {
            deckRepository.decksResult = Result.NetworkError(IOException("no route to host"))
            val offlineViewModel = DecksViewModel(deckRepository, cardRepository, SavedStateHandle())
            assertTrue("a network failure raises the offline bar", offlineViewModel.uiState.value.isOffline)

            deckRepository.decksResult = Result.UnexpectedError(IllegalStateException("bad payload"))
            val unexpectedViewModel = DecksViewModel(deckRepository, cardRepository, SavedStateHandle())

            val state = unexpectedViewModel.uiState.value
            assertFalse("an unexpected exception is not a connectivity failure", state.isOffline)
            assertFalse("the screen has finished loading", state.isLoading)
            assertTrue("the failure is still reported to the user", state.errorMessage != null)
        }

    private fun detailViewModel(): DecksViewModel {
        deckRepository.decksResult =
            Result.Success(listOf(Deck(BOOK_ID, "Evals for AI Engineers", 48, 6, 0.625f, chapters = 9, truncated = 0)))
        return DecksViewModel(deckRepository, cardRepository, SavedStateHandle(mapOf("bookId" to BOOK_ID)))
    }

    /**
     * ADR-008 / hard rule 5, restated for the post-#172 shape. `state` and `due`
     * are now on the browse card, carried from the server — so the invariant is
     * no longer "no such field exists" but "nothing on the client writes one".
     * Edit and unsuspend must leave both exactly as the response delivered them.
     */
    private fun assertSchedulingUntouched(
        viewModel: DecksViewModel,
        cardId: Long,
        served: DeckCard,
    ) {
        val onScreen =
            viewModel.uiState.value.expandedCards
                .first { it.id == cardId }
        assertEquals("state is the server's, unchanged", served.state, onScreen.state)
        assertEquals("due is the server's, unchanged", served.due, onScreen.due)
        // Nothing recomputes a schedule: the client owns no FSRS step counter.
        assertTrue(
            "browse cards hold no client-side FSRS step",
            DeckCard::class.java.declaredFields.none { it.name == "step" },
        )
    }

    private fun browseCard(
        id: Long,
        chapter: String = CHAPTER,
        suspendedUntil: Instant? = null,
        state: String = "review",
        due: Instant? = Instant.parse("2026-09-09T08:00:00Z"),
    ) = DeckCard(
        id = id,
        type = "qa",
        front = "front $id",
        back = "back $id",
        chapter = chapter,
        tags = emptyList(),
        suspendedUntil = suspendedUntil,
        state = state,
        due = due,
    )

    private data class EditCall(
        val cardId: Long,
        val front: String?,
        val back: String?,
        val tags: List<String>?,
    )

    private class FakeDeckRepository : DeckRepository {
        var decksResult: Result<List<Deck>> = Result.Success(emptyList())
        var deckCardsResult: Result<List<DeckCard>> = Result.Success(emptyList())
        var decksCalls = 0
        val deckCardsCalls = mutableListOf<Pair<Long, String?>>()

        override suspend fun decks(): Result<List<Deck>> {
            decksCalls++
            return decksResult
        }

        override suspend fun deckCards(
            bookId: Long,
            chapter: String?,
        ): Result<List<DeckCard>> {
            deckCardsCalls += bookId to chapter
            return deckCardsResult
        }
    }

    private class FakeCardRepository : CardRepository {
        var editResult: Result<Unit> = Result.Success(Unit)
        var suspendResult: Result<Instant?> = Result.Success(FAR_FUTURE)
        var unsuspendResult: Result<Instant?> = Result.Success(null)
        val editCalls = mutableListOf<EditCall>()
        val suspendCalls = mutableListOf<Long>()
        val unsuspendCalls = mutableListOf<Long>()

        override suspend fun dueCards(forceRefresh: Boolean): Result<DueSummary> =
            throw UnsupportedOperationException("decks tests never load due cards")

        override suspend fun refreshDueCards(): Result<DueSummary> = throw UnsupportedOperationException("decks tests never load due cards")

        override suspend fun editCard(
            cardId: Long,
            front: String?,
            back: String?,
            tags: List<String>?,
        ): Result<Unit> {
            editCalls += EditCall(cardId, front, back, tags)
            return editResult
        }

        override suspend fun suspendCard(cardId: Long): Result<Instant?> {
            suspendCalls += cardId
            return suspendResult
        }

        override suspend fun unsuspendCard(cardId: Long): Result<Instant?> {
            unsuspendCalls += cardId
            return unsuspendResult
        }
    }

    private companion object {
        const val BOOK_ID = 7L
        const val CHAPTER = "3. Error Analysis"
        const val OTHER_CHAPTER = "4. Evaluators"
        val FAR_FUTURE: Instant = Instant.parse("9999-12-31T00:00:00Z")
    }
}
