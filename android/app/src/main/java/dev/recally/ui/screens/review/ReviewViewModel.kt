package dev.recally.ui.screens.review

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.recally.data.sync.RatingOutbox
import dev.recally.domain.model.Card
import dev.recally.domain.model.DueSummary
import dev.recally.domain.model.ReviewRating
import dev.recally.domain.repository.CardRepository
import dev.recally.domain.repository.Result
import dev.recally.domain.repository.ReviewRepository
import dev.recally.domain.repository.SettingsRepository
import dev.recally.domain.repository.StatsRepository
import dev.recally.ui.formatNextDueIn
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.Duration
import java.time.Instant
import javax.inject.Inject

/**
 * Review session (docs/android.md, *Screens → 2*; ADR-005).
 *
 * Owns the session UI state and the same-session re-queue decision. The
 * re-queue timer is a display timer only — seeded from each card's `step` in
 * `GET /reviews/due`, advanced locally while offline, and overwritten by the
 * rate response's `step` whenever one comes back. The server owns the real
 * schedule; this ViewModel never computes it.
 *
 * `response_ms` is measured flip-to-rate: [flip] starts the clock, [rate]
 * stops it. A rating the server cannot be reached for goes to the Room
 * outbox ([RatingOutbox.record]) — never to an in-memory counter — and the
 * "N ratings queued" bar reads the persisted queue depth, so the figure
 * cannot disagree with what is stored. Every rating carries the `device_id`
 * from the last `POST /devices` response (docs/android.md, "Offline-first
 * sync").
 */
@HiltViewModel
class ReviewViewModel
    @Inject
    constructor(
        private val cardRepository: CardRepository,
        private val reviewRepository: ReviewRepository,
        private val ratingOutbox: RatingOutbox,
        private val settings: SettingsRepository,
        private val statsRepository: StatsRepository,
        private val clock: Clock,
    ) : ViewModel() {
        private val _uiState = MutableStateFlow(ReviewUiState())
        val uiState: StateFlow<ReviewUiState> = _uiState.asStateFlow()

        // Session state — rendered through UiState, never itself exposed.
        private var queue = ArrayDeque<Card>()
        private val repeats = mutableListOf<ScheduledRepeat>()
        private val localSteps = mutableMapOf<Long, Int>()
        private var learningStepsMinutes: List<Int> = emptyList()
        private var currentCard: Card? = null
        private var flippedAt: Instant? = null
        private var sessionStartedAt: Instant? = null
        private var reviewedCount = 0
        private var againCount = 0
        private var hardCount = 0
        private var goodOrEasyCount = 0
        private var lapseCount = 0
        private val doneCardIds = mutableSetOf<Long>()

        /** A card waiting to be shown again inside this session. */
        private data class ScheduledRepeat(
            val card: Card,
            val availableAt: Instant,
        )

        init {
            // The queued bar is the DAO's count, not a local counter: a
            // rating that failed to persist must not inflate it (#115).
            viewModelScope.launch {
                ratingOutbox.queuedCount().collect { count ->
                    _uiState.update { it.copy(queuedRatingCount = count) }
                }
            }
            loadSession()
        }

        fun loadSession() {
            viewModelScope.launch {
                _uiState.update { it.copy(isLoading = true, errorMessage = null) }
                when (val result = cardRepository.dueCards()) {
                    is Result.Success -> startSession(result.data)
                    is Result.Unauthorized ->
                        _uiState.update { it.copy(isLoading = false, isUnauthorized = true) }
                    is Result.HttpError ->
                        _uiState.update {
                            it.copy(isLoading = false, errorMessage = result.detail ?: "Couldn't load due cards (${result.status})")
                        }
                    is Result.NetworkError ->
                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                isOffline = true,
                                errorMessage = "Couldn't load due cards — check the connection and retry",
                            )
                        }
                }
            }
        }

        private fun startSession(summary: DueSummary) {
            queue = ArrayDeque(summary.cards)
            repeats.clear()
            localSteps.clear()
            localSteps.putAll(summary.cards.map { it.id to (it.step ?: 0) })
            learningStepsMinutes = summary.learningStepsMinutes
            sessionStartedAt = clock.instant()
            reviewedCount = 0
            againCount = 0
            hardCount = 0
            goodOrEasyCount = 0
            lapseCount = 0
            doneCardIds.clear()
            _uiState.update { it.copy(isLoading = false, summary = null) }
            showNextCard(clock.instant())
        }

        /**
         * Reveal the answer. Starts the response clock — the interval that
         * predicts recall is retrieval time, not time spent reading the front.
         */
        fun flip() {
            val current = currentCard ?: return
            if (_uiState.value.isFlipped) return
            flippedAt = clock.instant()
            _uiState.update {
                it.copy(
                    isFlipped = true,
                    answer = current.back,
                    ratingHints = intervalHints(current),
                )
            }
        }

        /** Rate the current card (1=Again, 2=Hard, 3=Good, 4=Easy). */
        fun rate(rating: Int) {
            val current = currentCard ?: return
            if (!_uiState.value.isFlipped) return
            val now = clock.instant()
            val responseMs = Duration.between(flippedAt ?: now, now).toMillis()

            reviewedCount++
            when (rating) {
                RATING_AGAIN -> againCount++
                RATING_HARD -> hardCount++
                else -> goodOrEasyCount++
            }

            viewModelScope.launch {
                // The id from the last POST /devices response; it ends up on
                // review_logs.device_id so the gate can attribute the row.
                val reviewRating =
                    ReviewRating(
                        cardId = current.id,
                        rating = rating,
                        responseMs = responseMs,
                        ratedAt = now,
                        deviceId = settings.load().deviceId,
                    )
                when (val result = reviewRepository.rate(reviewRating)) {
                    is Result.Success -> {
                        val outcome = result.data
                        // A replayed rating reports duplicate: true and moved
                        // nothing, so only non-duplicates feed the lapse count.
                        if (!outcome.duplicate && outcome.lapsed) lapseCount++
                        // The server's step replaces the local counter.
                        outcome.step?.let { localSteps[current.id] = it }
                        _uiState.update { it.copy(isOffline = false) }
                    }
                    is Result.NetworkError -> {
                        // Persist first (the outbox schedules its own flush); a
                        // persist failure must not crash the session.
                        runCatching { ratingOutbox.record(reviewRating) }
                        _uiState.update { it.copy(isOffline = true) }
                    }
                    is Result.Unauthorized ->
                        _uiState.update { it.copy(isUnauthorized = true) }
                    is Result.HttpError ->
                        _uiState.update {
                            it.copy(errorMessage = result.detail ?: "Rating failed (${result.status})")
                        }
                }
                handleRequeue(current, rating, now)
                showNextCard(now)
            }
        }

        /**
         * Same-session relearning (ADR-005): an Again/Hard card comes back
         * after the step interval for its *current* step, then the local step
         * advances by one. Past the last entry in `learning_steps_minutes` the
         * card is not re-queued again. Good/Easy cards leave the session.
         */
        private fun handleRequeue(
            card: Card,
            rating: Int,
            ratedAt: Instant,
        ) {
            if (rating != RATING_AGAIN && rating != RATING_HARD) {
                doneCardIds += card.id
                return
            }
            val lastStepIndex = learningStepsMinutes.lastIndex
            val step = localSteps[card.id] ?: 0
            if (lastStepIndex < 0 || step > lastStepIndex) {
                doneCardIds += card.id
                return
            }
            val intervalMinutes =
                when (rating) {
                    RATING_AGAIN -> learningStepsMinutes[step]
                    else -> learningStepsMinutes[(step + 1).coerceAtMost(lastStepIndex)]
                }
            repeats += ScheduledRepeat(card, ratedAt.plus(Duration.ofMinutes(intervalMinutes.toLong())))
            localSteps[card.id] = step + 1
        }

        /**
         * Next card: a repeat whose interval has elapsed, else the head of the
         * queue, else the earliest repeat (the session ended before its step
         * did), else the session is finished.
         */
        private fun showNextCard(now: Instant) {
            val dueRepeatIndex = repeats.indexOfFirst { !it.availableAt.isAfter(now) }
            val next =
                when {
                    dueRepeatIndex >= 0 -> repeats.removeAt(dueRepeatIndex).card
                    queue.isNotEmpty() -> queue.removeFirst()
                    repeats.isNotEmpty() -> repeats.removeAt(0).card
                    else -> null
                }
            if (next == null) {
                finishSession(now)
                return
            }
            currentCard = next
            flippedAt = null
            _uiState.update {
                it.copy(
                    card = next.toReviewCardUi(),
                    isFlipped = false,
                    answer = null,
                    ratingHints = null,
                    isEditing = false,
                    doneCount = doneCardIds.size,
                    toRepeatCount = repeats.size,
                    cardsLeft = queue.size + repeats.size,
                )
            }
        }

        private fun finishSession(now: Instant) {
            currentCard = null
            flippedAt = null
            val startedAt = sessionStartedAt ?: now
            _uiState.update {
                it.copy(
                    card = null,
                    isFlipped = false,
                    answer = null,
                    ratingHints = null,
                    isEditing = false,
                    doneCount = doneCardIds.size,
                    toRepeatCount = 0,
                    cardsLeft = 0,
                    summary =
                        SessionSummaryUi(
                            reviewedCount = reviewedCount,
                            elapsedMs = Duration.between(startedAt, now).toMillis(),
                            goodOrEasyCount = goodOrEasyCount,
                            hardCount = hardCount,
                            againCount = againCount,
                            lapseCount = lapseCount,
                        ),
                )
            }
            loadNextDueLabel()
        }

        /**
         * The sheet's "Next card due in 4 hours" line (issue #134): the
         * timestamp comes from `GET /stats`'s `next_due_at` — served, never
         * computed client-side (hard rule 5). A failure or a null value just
         * omits the line; the summary is complete without it.
         */
        private fun loadNextDueLabel() {
            viewModelScope.launch {
                val result = statsRepository.stats()
                if (result is Result.Success) {
                    val label =
                        result.data.nextDueAt?.let { nextDueAt ->
                            "Next card due ${formatNextDueIn(clock.instant(), nextDueAt)}"
                        }
                    _uiState.update { state ->
                        state.copy(summary = state.summary?.copy(nextDueLabel = label))
                    }
                }
            }
        }

        /**
         * Bury drops the card from the rest of the session — the honest
         * alternative to a dishonest rating. Offered only before the flip and
         * only online; it is never queued, so offline it simply does nothing
         * (the overflow item is disabled by [ReviewUiState.buryAvailable]).
         */
        fun bury() {
            val current = currentCard ?: return
            if (!_uiState.value.buryAvailable) return
            viewModelScope.launch {
                when (val result = reviewRepository.bury(current.id)) {
                    is Result.Success -> showNextCard(clock.instant())
                    is Result.NetworkError ->
                        _uiState.update {
                            it.copy(isOffline = true, errorMessage = "Bury needs a connection")
                        }
                    is Result.Unauthorized ->
                        _uiState.update { it.copy(isUnauthorized = true) }
                    is Result.HttpError ->
                        _uiState.update {
                            it.copy(errorMessage = result.detail ?: "Bury failed (${result.status})")
                        }
                }
            }
        }

        fun startEdit() {
            if (_uiState.value.editAvailable) {
                _uiState.update { it.copy(isEditing = true) }
            }
        }

        fun dismissEdit() {
            _uiState.update { it.copy(isEditing = false) }
        }

        /** Wording fix in place via `PATCH /cards/{id}`; scheduling untouched (ADR-008). */
        fun editCard(
            front: String,
            back: String,
        ) {
            val current = currentCard ?: return
            if (!_uiState.value.editAvailable) return
            viewModelScope.launch {
                when (val result = reviewRepository.editCard(current.id, front, back)) {
                    is Result.Success -> applyEdit(current.copy(front = front, back = back))
                    is Result.NetworkError ->
                        _uiState.update {
                            it.copy(isOffline = true, isEditing = false, errorMessage = "Edit needs a connection")
                        }
                    is Result.Unauthorized ->
                        _uiState.update { it.copy(isUnauthorized = true, isEditing = false) }
                    is Result.HttpError ->
                        _uiState.update {
                            it.copy(isEditing = false, errorMessage = result.detail ?: "Edit failed (${result.status})")
                        }
                }
            }
        }

        /** The edit follows the card, including a pending repeat of it. */
        private fun applyEdit(updated: Card) {
            currentCard = updated
            queue = queue.mapTo(ArrayDeque()) { if (it.id == updated.id) updated else it }
            repeats.replaceAll { if (it.card.id == updated.id) it.copy(card = updated) else it }
            _uiState.update {
                it.copy(
                    card = updated.toReviewCardUi(),
                    answer = updated.back,
                    isEditing = false,
                )
            }
        }

        /**
         * Again/Hard hints from `learning_steps_minutes` — the only intervals
         * derivable client-side (design-system.md, "Rating row"). Good/Easy
         * stay null: the server returns those only after the rating lands.
         */
        private fun intervalHints(card: Card): RatingHints {
            val lastStepIndex = learningStepsMinutes.lastIndex
            val step = (localSteps[card.id] ?: 0).coerceAtMost(lastStepIndex)
            return RatingHints(
                again = learningStepsMinutes.getOrNull(step)?.let(::formatStepInterval),
                hard =
                    learningStepsMinutes.getOrNull(step + 1)?.let(::formatStepInterval)
                        ?: learningStepsMinutes.getOrNull(lastStepIndex)?.let(::formatStepInterval),
                good = null,
                easy = null,
            )
        }

        private fun Card.toReviewCardUi() =
            ReviewCardUi(
                id = id,
                type = type,
                front = front,
                bookId = bookId,
                book = book,
                chapter = chapter,
            )

        private companion object {
            const val RATING_AGAIN = 1
            const val RATING_HARD = 2

            /** `[1, 10]` → "<1m", "10m" (design-system.md, "Rating row"). */
            fun formatStepInterval(minutes: Int): String =
                when {
                    minutes <= 1 -> "<1m"
                    minutes < 60 -> "${minutes}m"
                    else -> "${minutes / 60}h"
                }
        }
    }
