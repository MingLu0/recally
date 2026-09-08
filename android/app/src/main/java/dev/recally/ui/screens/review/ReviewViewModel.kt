package dev.recally.ui.screens.review

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.recally.domain.repository.CardRepository
import dev.recally.domain.repository.Result
import dev.recally.domain.repository.ReviewRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Clock
import javax.inject.Inject

/**
 * Review session ViewModel — stub so the step 4g tests compile and run red.
 * The session logic (flip clock, re-queue, offline counter, bury/edit) lands
 * in the implementation commit.
 */
@HiltViewModel
class ReviewViewModel
    @Inject
    constructor(
        private val cardRepository: CardRepository,
        private val reviewRepository: ReviewRepository,
        private val clock: Clock,
    ) : ViewModel() {
        private val _uiState = MutableStateFlow(ReviewUiState())
        val uiState: StateFlow<ReviewUiState> = _uiState.asStateFlow()

        init {
            loadSession()
        }

        fun loadSession() {
            viewModelScope.launch {
                when (val result = cardRepository.dueCards()) {
                    is Result.Success -> {
                        val first = result.data.cards.firstOrNull()
                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                card =
                                    first?.let { card ->
                                        ReviewCardUi(card.id, card.type, card.front, card.bookId, card.book, card.chapter)
                                    },
                                answer = first?.back,
                                ratingHints = RatingHints(again = "<1m", hard = "10m", good = "1d", easy = "4d"),
                                totalCount = result.data.cards.size,
                                cardsLeft = (result.data.cards.size - 1).coerceAtLeast(0),
                            )
                        }
                    }
                    is Result.Unauthorized ->
                        _uiState.update { it.copy(isLoading = false, isUnauthorized = true) }
                    is Result.HttpError ->
                        _uiState.update { it.copy(isLoading = false, errorMessage = result.detail) }
                    is Result.NetworkError ->
                        _uiState.update { it.copy(isLoading = false, isOffline = true) }
                }
            }
        }

        fun flip() {
            _uiState.update { it.copy(isFlipped = true) }
        }

        fun rate(rating: Int) = Unit

        fun bury() = Unit

        fun startEdit() {
            _uiState.update { it.copy(isEditing = true) }
        }

        fun dismissEdit() {
            _uiState.update { it.copy(isEditing = false) }
        }

        fun editCard(
            front: String,
            back: String,
        ) = Unit
    }
