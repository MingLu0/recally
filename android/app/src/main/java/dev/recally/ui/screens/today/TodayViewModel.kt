package dev.recally.ui.screens.today

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.recally.di.IoDispatcher
import dev.recally.domain.repository.ApprovalRepository
import dev.recally.domain.repository.CardRepository
import dev.recally.domain.repository.Result
import dev.recally.domain.repository.StatsRepository
import dev.recally.ui.formatNextDueIn
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
 * the queue requires connectivity and Today must render without it.
 * Instantiated at the Today `NavHost` route entry.
 */
@HiltViewModel
class TodayViewModel
    @Inject
    constructor(
        private val cardRepository: CardRepository,
        private val statsRepository: StatsRepository,
        private val approvalRepository: ApprovalRepository,
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
                -> Unit
            }
        }

        private companion object {
            const val MESSAGE_UNEXPECTED = "Something went wrong — pull to retry."
        }
    }
