package dev.recally.ui.screens.decks

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.recally.domain.model.DeckCard
import dev.recally.domain.repository.CardRepository
import dev.recally.domain.repository.DeckRepository
import dev.recally.domain.repository.Result
import dev.recally.domain.repository.displayMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Backs both deck routes: the plain `decks` route (book list) and the
 * parameterised `decks/{bookId}` route (book detail with in-place chapter
 * expansion). Scoped to its route entry, so [bookId] arrives via
 * [SavedStateHandle] for free (docs/android.md, "ViewModels are scoped to
 * their route").
 *
 * Browse is read-only for review actions — there is no rate and no approve
 * here. The ADR-008 controls (edit, suspend, unsuspend) are the only writes;
 * all require connectivity and are disabled offline rather than queued.
 */
@HiltViewModel
class DecksViewModel
    @Inject
    constructor(
        private val deckRepository: DeckRepository,
        private val cardRepository: CardRepository,
        savedStateHandle: SavedStateHandle,
    ) : ViewModel() {
        private val bookId: Long? = savedStateHandle.get<Long>("bookId")

        private val mutableUiState =
            MutableStateFlow(
                DecksUiState(isBookDetail = bookId != null, bookId = bookId, isLoading = true),
            )
        val uiState: StateFlow<DecksUiState> = mutableUiState.asStateFlow()

        init {
            refresh()
        }

        fun refresh() {
            val currentBookId = bookId
            if (currentBookId == null) refreshDeckList() else refreshBook(currentBookId)
        }

        /**
         * Expands a chapter by issuing the server-side `?chapter=` filter
         * (docs/android.md, Screens → 4), or collapses the open chapter
         * locally when it is tapped again.
         */
        fun toggleChapter(chapter: String) {
            val currentBookId = bookId ?: return
            if (uiState.value.expandedChapter == chapter) {
                mutableUiState.update { it.copy(expandedChapter = null, expandedCards = emptyList()) }
                return
            }
            viewModelScope.launch {
                mutableUiState.update { it.copy(expandedChapter = chapter, isChapterLoading = true) }
                when (val result = deckRepository.deckCards(currentBookId, chapter)) {
                    is Result.Success ->
                        mutableUiState.update {
                            it.copy(
                                expandedCards = result.data,
                                isChapterLoading = false,
                                isOffline = false,
                                isUnauthorized = false,
                                errorMessage = null,
                            )
                        }

                    is Result.Unauthorized ->
                        mutableUiState.update { it.copy(isChapterLoading = false, isUnauthorized = true) }

                    is Result.NetworkError ->
                        // Keep whatever was expanded — the last fetched list
                        // stays browsable behind the offline bar.
                        mutableUiState.update { it.copy(isChapterLoading = false, isOffline = true) }

                    is Result.HttpError ->
                        mutableUiState.update {
                            it.copy(isChapterLoading = false, errorMessage = result.detail ?: "HTTP ${result.status}")
                        }

                    is Result.UnexpectedError ->
                        mutableUiState.update {
                            it.copy(isChapterLoading = false, errorMessage = result.displayMessage())
                        }
                }
            }
        }

        fun startEdit(card: DeckCard) {
            mutableUiState.update { it.copy(editingCard = card) }
        }

        fun dismissEdit() {
            mutableUiState.update { it.copy(editingCard = null) }
        }

        /**
         * ADR-008 edit: `PATCH /cards/{id}` updates text only; scheduling is
         * untouched and nothing is refetched. A success applies the submitted
         * text to the row in place.
         */
        fun submitEdit(
            front: String,
            back: String,
        ) {
            val editing = uiState.value.editingCard ?: return
            if (!uiState.value.cardControlsEnabled) return
            viewModelScope.launch {
                when (val result = cardRepository.editCard(editing.id, front = front, back = back)) {
                    is Result.Success ->
                        mutableUiState.update { state ->
                            state.copy(
                                expandedCards =
                                    state.expandedCards.map {
                                        if (it.id == editing.id) it.copy(front = front, back = back) else it
                                    },
                                editingCard = null,
                                isOffline = false,
                                isUnauthorized = false,
                                errorMessage = null,
                            )
                        }

                    is Result.Unauthorized ->
                        mutableUiState.update { it.copy(isUnauthorized = true) }

                    is Result.NetworkError ->
                        // Keep the dialog open so the edit is not lost.
                        mutableUiState.update { it.copy(isOffline = true) }

                    is Result.HttpError ->
                        mutableUiState.update {
                            it.copy(editingCard = null, errorMessage = result.detail ?: "HTTP ${result.status}")
                        }

                    is Result.UnexpectedError ->
                        mutableUiState.update {
                            it.copy(editingCard = null, errorMessage = result.displayMessage())
                        }
                }
            }
        }

        fun suspendCard(cardId: Long) = updateSuspension(cardId) { cardRepository.suspendCard(it) }

        fun unsuspendCard(cardId: Long) = updateSuspension(cardId) { cardRepository.unsuspendCard(it) }

        /**
         * Writes the server's `suspended_until` verdict into the row in
         * place. Unsuspend returns null and nothing is recomputed — the card
         * is in rotation at whatever due date the server already held
         * (ADR-008).
         */
        private fun updateSuspension(
            cardId: Long,
            call: suspend (Long) -> Result<java.time.Instant?>,
        ) {
            if (!uiState.value.cardControlsEnabled) return
            viewModelScope.launch {
                when (val result = call(cardId)) {
                    is Result.Success ->
                        mutableUiState.update { state ->
                            state.copy(
                                expandedCards =
                                    state.expandedCards.map {
                                        if (it.id == cardId) it.copy(suspendedUntil = result.data) else it
                                    },
                                isOffline = false,
                                isUnauthorized = false,
                                errorMessage = null,
                            )
                        }

                    is Result.Unauthorized ->
                        mutableUiState.update { it.copy(isUnauthorized = true) }

                    is Result.NetworkError ->
                        mutableUiState.update { it.copy(isOffline = true) }

                    is Result.HttpError ->
                        mutableUiState.update { it.copy(errorMessage = result.detail ?: "HTTP ${result.status}") }

                    is Result.UnexpectedError ->
                        mutableUiState.update { it.copy(errorMessage = result.displayMessage()) }
                }
            }
        }

        private fun refreshDeckList() {
            viewModelScope.launch {
                mutableUiState.update { it.copy(isLoading = true) }
                when (val result = deckRepository.decks()) {
                    is Result.Success ->
                        mutableUiState.update {
                            it.copy(
                                decks = result.data,
                                isLoading = false,
                                isOffline = false,
                                isUnauthorized = false,
                                errorMessage = null,
                            )
                        }

                    is Result.Unauthorized ->
                        mutableUiState.update { it.copy(isLoading = false, isUnauthorized = true) }

                    is Result.NetworkError ->
                        mutableUiState.update { it.copy(isLoading = false, isOffline = true) }

                    is Result.HttpError ->
                        mutableUiState.update {
                            it.copy(isLoading = false, errorMessage = result.detail ?: "HTTP ${result.status}")
                        }

                    is Result.UnexpectedError ->
                        mutableUiState.update {
                            it.copy(isLoading = false, errorMessage = result.displayMessage())
                        }
                }
            }
        }

        private fun refreshBook(currentBookId: Long) {
            viewModelScope.launch {
                mutableUiState.update { it.copy(isLoading = true) }
                // The cards endpoint returns neither the book's title nor its
                // progress/due; the documented way to fill the header is the
                // decks list (issue #152).
                resolveBookHeader(currentBookId)
                when (val result = deckRepository.deckCards(currentBookId, chapter = null)) {
                    is Result.Success -> {
                        val chapters =
                            result.data
                                .groupBy { it.chapter?.takeIf(String::isNotBlank) ?: UNGROUPED_CHAPTER }
                                .map { (name, cards) -> ChapterSummary(name = name, cardCount = cards.size) }
                        // The unfiltered fetch builds the chapter overview
                        // only; rows arrive per chapter via toggleChapter.
                        // A refresh with a chapter open refetches that
                        // chapter's rows through the same server filter.
                        val openChapter = uiState.value.expandedChapter
                        mutableUiState.update {
                            it.copy(
                                chapters = chapters,
                                expandedChapter = null,
                                expandedCards = emptyList(),
                                isLoading = false,
                                isOffline = false,
                                isUnauthorized = false,
                                errorMessage = null,
                            )
                        }
                        if (openChapter != null) toggleChapter(openChapter)
                    }

                    is Result.Unauthorized ->
                        mutableUiState.update { it.copy(isLoading = false, isUnauthorized = true) }

                    is Result.NetworkError ->
                        // Keep the last loaded chapter list and rows —
                        // browsing what is already on screen works offline.
                        mutableUiState.update { it.copy(isLoading = false, isOffline = true) }

                    is Result.HttpError ->
                        mutableUiState.update {
                            it.copy(isLoading = false, errorMessage = result.detail ?: "HTTP ${result.status}")
                        }

                    is Result.UnexpectedError ->
                        mutableUiState.update {
                            it.copy(isLoading = false, errorMessage = result.displayMessage())
                        }
                }
            }
        }

        /** Best effort — a missing header never blocks the card list. */
        private suspend fun resolveBookHeader(currentBookId: Long) {
            val result = deckRepository.decks()
            if (result is Result.Success) {
                val deck = result.data.firstOrNull { it.bookId == currentBookId }
                if (deck != null) {
                    mutableUiState.update {
                        it.copy(bookTitle = deck.title, bookDue = deck.due, bookProgress = deck.progress)
                    }
                }
            }
        }
    }
