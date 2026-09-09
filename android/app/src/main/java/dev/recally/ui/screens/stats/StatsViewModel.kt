package dev.recally.ui.screens.stats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.recally.di.IoDispatcher
import dev.recally.domain.model.ForecastDay
import dev.recally.domain.repository.Result
import dev.recally.domain.repository.StatsRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Clock
import java.time.LocalDate
import javax.inject.Inject

/**
 * Stats ViewModel (docs/android.md, "Architecture"): owns the one
 * [StatsUiState], fed by `GET /stats` via [StatsRepository]. Instantiated at
 * the Stats `NavHost` route entry.
 *
 * The screen is remote-only — Room caches due cards, nothing else
 * (docs/android.md, "Offline-first sync") — so a failed refresh surfaces the
 * offline or error state and clears the figures rather than showing stale
 * numbers from a previous load.
 */
@HiltViewModel
class StatsViewModel
    @Inject
    constructor(
        private val statsRepository: StatsRepository,
        @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
        private val clock: Clock,
    ) : ViewModel() {
        private val mutableUiState = MutableStateFlow(StatsUiState(isLoading = true))
        val uiState: StateFlow<StatsUiState> = mutableUiState.asStateFlow()

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
                loadStats()
            }
        }

        private suspend fun loadStats() {
            when (val result = withContext(ioDispatcher) { statsRepository.stats() }) {
                is Result.Success ->
                    mutableUiState.update {
                        StatsUiState(
                            isLoading = false,
                            streakDays = result.data.streakDays,
                            reviewsToday = result.data.reviewsToday,
                            retention30d = result.data.retention30d,
                            forecast = mapForecast(result.data.forecast, LocalDate.now(clock)),
                            lapseRateByType = result.data.lapseRateByType,
                            lapseRateByGuidanceVersion = mapGuidanceVersions(result.data.lapseRateByGuidanceVersion),
                            nextDueAt = result.data.nextDueAt,
                        )
                    }
                Result.Unauthorized ->
                    mutableUiState.update {
                        it.copy(isLoading = false, isUnauthorized = true, isOffline = false)
                    }
                // Stats are never cached, so offline shows the bar and no
                // figures from a previous load.
                is Result.NetworkError ->
                    mutableUiState.update {
                        StatsUiState(isLoading = false, isOffline = true)
                    }
                is Result.HttpError ->
                    mutableUiState.update {
                        it.copy(
                            isLoading = false,
                            errorMessage = result.detail ?: "Couldn't load stats (HTTP ${result.status})",
                        )
                    }
            }
        }

        /**
         * The next seven calendar days from today, looked up in the served
         * `forecast[]` by date: absent days are zero bars in position (never
         * skipped, or the weekday labels shift) and anything beyond the
         * seventh day is dropped (issue #95, "The forecast chart").
         */
        private fun mapForecast(
            forecast: List<ForecastDay>,
            today: LocalDate,
        ): List<ForecastBar> {
            val dueByDate = forecast.associate { LocalDate.parse(it.date) to it.due }
            return (0L until FORECAST_DAYS).map { offset ->
                val date = today.plusDays(offset)
                ForecastBar(date = date, due = dueByDate[date] ?: 0, isToday = offset == 0L)
            }
        }

        /** Keys arrive as strings ("2", not "2") — sort numerically, or v10 sorts before v2. */
        private fun mapGuidanceVersions(lapseRateByGuidanceVersion: Map<String, Double>): List<GuidanceVersionLapseRate> =
            lapseRateByGuidanceVersion
                .mapNotNull { (key, rate) -> key.toIntOrNull()?.let { GuidanceVersionLapseRate(it, rate) } }
                .sortedBy { it.version }

        private companion object {
            const val FORECAST_DAYS = 7L
            const val MESSAGE_UNEXPECTED = "Something went wrong — pull to retry."
        }
    }
