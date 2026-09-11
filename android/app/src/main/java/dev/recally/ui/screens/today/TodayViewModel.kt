package dev.recally.ui.screens.today

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.recally.di.IoDispatcher
import dev.recally.domain.repository.ApprovalRepository
import dev.recally.domain.repository.CardRepository
import dev.recally.domain.repository.DeckRepository
import dev.recally.domain.repository.Result
import dev.recally.domain.repository.StatsRepository
import dev.recally.domain.repository.displayMessage
import dev.recally.ui.formatNextDueIn
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.MissingFieldException
import java.time.Clock
import javax.inject.Inject

/**
 * Today ViewModel (docs/android.md, "Architecture"): owns the one
 * [TodayUiState]. Due and new counts come from `GET /reviews/due` via
 * [CardRepository] (Room-backed, so Today renders offline and never depends on
 * a push having arrived — docs/android.md, "Push notifications"); the streak
 * figures come from `GET /stats` via [StatsRepository]; the pending-queue
 * buckets come from the collection-wide `counts` on `GET /cards/pending` via
 * [ApprovalRepository] (G1, issue #132) — a failure there is silent, because
 * the queue requires connectivity and Today must render without it. The
 * "Your books" rail comes from `GET /decks` via [DeckRepository] (G2, issue
 * #133; rail built in #154) — likewise silent on failure, since decks are
 * remote-only and the rail never blanks the rest of the screen.
 * Instantiated at the Today `NavHost` route entry.
 */
@HiltViewModel
class TodayViewModel
    @Inject
    constructor(
        private val cardRepository: CardRepository,
        private val statsRepository: StatsRepository,
        private val approvalRepository: ApprovalRepository,
        private val deckRepository: DeckRepository,
        @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
        private val clock: Clock,
    ) : ViewModel() {
        private val mutableUiState = MutableStateFlow(TodayUiState())
        val uiState: StateFlow<TodayUiState> = mutableUiState.asStateFlow()

        private val exceptionHandler =
            CoroutineExceptionHandler { _, _ ->
                mutableUiState.update {
                    it.copy(isLoading = false, errorMessage = MESSAGE_UNEXPECTED)
                }
            }

        init {
            refresh()
        }

        fun refresh() {
            viewModelScope.launch(exceptionHandler) {
                mutableUiState.update { it.copy(isLoading = true, errorMessage = null) }
                loadDueCounts()
                loadStats()
                loadPendingCounts()
                loadDecks()
            }
        }

        /**
         * A forced refresh; the repository falls back to the Room cache and
         * marks the result [Result.Success.servedFromCache], which is what sets
         * the offline bar (docs/android.md, "Repositories own the data layer").
         */
        private suspend fun loadDueCounts() {
            when (val result = withContext(ioDispatcher) { cardRepository.refreshDueCards() }) {
                is Result.Success ->
                    mutableUiState.update {
                        it.copy(
                            isLoading = false,
                            dueCount = result.data.dueCount,
                            newCount = result.data.newCount,
                            isOffline = result.servedFromCache,
                            showCheckSettingsBanner = false,
                        )
                    }
                Result.Unauthorized ->
                    mutableUiState.update {
                        it.copy(
                            isLoading = false,
                            showCheckSettingsBanner = true,
                            isOffline = false,
                        )
                    }
                // A network failure reaches here only with an empty cache.
                is Result.NetworkError ->
                    mutableUiState.update {
                        it.copy(isLoading = false, isOffline = true)
                    }
                is Result.HttpError ->
                    mutableUiState.update {
                        it.copy(
                            isLoading = false,
                            errorMessage =
                                result.detail
                                    ?: "Couldn't load today's cards (HTTP ${result.status})",
                        )
                    }
                // Not a connectivity failure: the server answered, the answer
                // could not be read (issue #188).
                is Result.UnexpectedError ->
                    mutableUiState.update {
                        it.copy(isLoading = false, errorMessage = result.displayMessage())
                    }
            }
        }

        /** Stats figures keep their last-known values on failure. */
        private suspend fun loadStats() {
            when (val result = withContext(ioDispatcher) { statsRepository.stats() }) {
                is Result.Success ->
                    mutableUiState.update {
                        it.copy(
                            streakDays = result.data.streakDays,
                            reviewsToday = result.data.reviewsToday,
                            retention30d = result.data.retention30d,
                            nextDueLabel =
                                result.data.nextDueAt?.let { nextDueAt ->
                                    "next card ${formatNextDueIn(clock.instant(), nextDueAt)}"
                                },
                        )
                    }
                Result.Unauthorized ->
                    mutableUiState.update { it.copy(showCheckSettingsBanner = true) }
                is Result.HttpError,
                is Result.NetworkError,
                is Result.UnexpectedError,
                -> Unit
            }
        }

        /**
         * The queue buckets ride the response's collection-wide `counts`,
         * never the list length (issue #132). The queue requires connectivity,
         * so any failure keeps the last-known values and shows nothing rather
         * than an error — Today works offline, Approve does not.
         */
        private suspend fun loadPendingCounts() {
            when (val result = withContext(ioDispatcher) { approvalRepository.pendingCards() }) {
                is Result.Success ->
                    mutableUiState.update {
                        it.copy(
                            pendingReviewCount = result.data.counts.pendingReview,
                            needsHumanCount = result.data.counts.needsHuman,
                        )
                    }
                Result.Unauthorized,
                is Result.HttpError,
                is Result.NetworkError,
                is Result.UnexpectedError,
                -> Unit
            }
        }

        /**
         * The "Your books" rail (G2, issue #133; rail built in #154). The
         * served `progress` is written into the state unmodified — never
         * recomputed client-side from `total`/`due`. Decks are remote-only
         * (docs/android.md, "Offline-first sync"), so a failure keeps the
         * last-known books and never raises a screen-level error — the rail
         * must not blank the rest of Today.
         *
         * It does, however, record that the load failed
         * ([TodayUiState.booksFailedToLoad], issue #189). Silence alone made a
         * rail that could not load indistinguishable from a library with no
         * books; the flag lets the rail say which it is while the silence rule
         * above stays intact.
         */
        private suspend fun loadDecks() {
            when (val result = withContext(ioDispatcher) { deckRepository.decks() }) {
                is Result.Success ->
                    mutableUiState.update {
                        it.copy(
                            books = result.data,
                            booksFailedToLoad = false,
                            booksFailureMessage = null,
                        )
                    }
                // A stale backend is the one failure here that can explain
                // itself, so its message rides through to the rail (issue
                // #195). Every other failure keeps the rail's own wording.
                is Result.UnexpectedError ->
                    mutableUiState.update {
                        it.copy(
                            booksFailedToLoad = true,
                            booksFailureMessage = result.staleBackendMessage(),
                        )
                    }
                Result.Unauthorized,
                is Result.HttpError,
                is Result.NetworkError,
                ->
                    mutableUiState.update {
                        it.copy(booksFailedToLoad = true, booksFailureMessage = null)
                    }
            }
        }

        /**
         * [displayMessage] for a decoding fault only. Any other cause is a
         * programming-level fault the rail cannot usefully narrate, so it gets
         * null and the strip keeps "Couldn't load your books".
         */
        private fun Result.UnexpectedError.staleBackendMessage(): String? = if (cause is MissingFieldException) displayMessage() else null

        private companion object {
            const val MESSAGE_UNEXPECTED = "Something went wrong — pull to retry."
        }
    }
