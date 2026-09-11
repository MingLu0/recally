package dev.recally.ui.screens.stats

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.recally.ui.theme.CombinedPreviews
import dev.recally.ui.theme.RecallyRadius
import dev.recally.ui.theme.RecallySpacing
import dev.recally.ui.theme.RecallyTheme
import dev.recally.ui.theme.recallyColors
import java.time.LocalDate
import kotlin.math.roundToInt

/**
 * The Stats screen (docs/android.md, "Screens → 5. Stats"; artboards
 * `RcStats.dc.html` / `DkStats.dc.html`). Pure composable — UiState in,
 * callbacks out (docs/android.md, "Pure screen composables").
 *
 * Stats invents nothing (design-system.md): every figure on screen maps
 * one-to-one onto `GET /stats`. The yield figure stays off (not on the
 * artboard), and there is no date-range picker — the forecast is fixed at
 * the next seven days.
 */
@Composable
fun StatsScreen(
    uiState: StatsUiState,
    onRetry: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.recallyColors
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .background(colors.ground)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = RecallySpacing.screenPadding),
    ) {
        Spacer(Modifier.height(RecallySpacing.screenPadding))
        Text("Stats", style = MaterialTheme.typography.titleLarge, color = colors.ink)
        Spacer(Modifier.height(RecallySpacing.md))

        if (uiState.isOffline) OfflineBar()
        if (uiState.isUnauthorized) UnauthorizedBanner(onOpenSettings)
        uiState.errorMessage?.let { ErrorRow(message = it, onRetry = onRetry) }

        if (uiState.isLoading && !uiState.isOffline) {
            // Skeleton blocks at the real sections' dimensions — no spinners
            // (design-system.md, "States").
            Column(verticalArrangement = Arrangement.spacedBy(RecallySpacing.lg)) {
                SkeletonBlock(height = 76.dp)
                SkeletonBlock(height = 200.dp)
                SkeletonBlock(height = 132.dp)
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(RecallySpacing.lg)) {
                HeadlineStrip(
                    streakDays = uiState.streakDays,
                    reviewsToday = uiState.reviewsToday,
                    retention30d = uiState.retention30d,
                    retentionReviewCount = uiState.retentionReviewCount,
                )
                ForecastSection(forecast = uiState.forecast)
                LapseRateByTypeSection(lapseRateByType = uiState.lapseRateByType)
                if (uiState.showGuidanceVersionSection) {
                    LapseRateByGuidanceVersionSection(versions = uiState.lapseRateByGuidanceVersion)
                }
            }
        }
        Spacer(Modifier.height(RecallySpacing.screenPadding))
    }
}

/** The three headline figures in one bordered unit — colour on numbers, not chrome. */
@Composable
private fun HeadlineStrip(
    streakDays: Int,
    reviewsToday: Int,
    retention30d: Double?,
    retentionReviewCount: Int,
) {
    val colors = MaterialTheme.recallyColors
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(RecallyRadius.md))
                .background(colors.surface)
                .border(1.dp, colors.line, RoundedCornerShape(RecallyRadius.md)),
    ) {
        HeadlineMetric(
            value = streakDays.toString(),
            label = "day streak",
            valueColor = colors.accent,
            modifier = Modifier.weight(1f),
        )
        HeadlineDivider()
        HeadlineMetric(
            value = reviewsToday.toString(),
            label = "today",
            valueColor = colors.primary,
            modifier = Modifier.weight(1f),
        )
        HeadlineDivider()
        HeadlineMetric(
            // Null is an absence, never "0%" (design-system.md, "States" →
            // "No data for a metric"; issue #190).
            value = retention30d?.let { "${(it * 100).roundToInt()}%" } ?: NO_DATA,
            label = "retention 30d",
            caption = retentionCaption(retention30d, retentionReviewCount),
            valueColor = if (retention30d != null) colors.success else colors.inkFaint,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun HeadlineMetric(
    value: String,
    label: String,
    valueColor: Color,
    modifier: Modifier = Modifier,
    caption: String? = null,
) {
    val colors = MaterialTheme.recallyColors
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(RecallySpacing.xs),
        modifier = modifier.padding(vertical = RecallySpacing.cardPadding),
    ) {
        Text(value, style = MaterialTheme.typography.titleMedium, color = valueColor)
        Text(label, style = MaterialTheme.typography.labelMedium, color = colors.inkFaint)
        // Present only for the no-data and small-sample states
        // (design-system.md, "The retention figure").
        caption?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelSmall,
                color = colors.inkFaint,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * How far to trust the retention figure (design-system.md, "The retention
 * figure"). A null figure says only that nothing was reviewed. A figure over
 * fewer than [RETENTION_CONFIDENT_REVIEWS] reviews still renders — it is the
 * honest number — but says so, because one lapse in three is 67% and reads as
 * a trend when it is noise. A confident sample carries no qualifier: the
 * label is the whole story (issue #201, superseding #190's descriptive
 * qualifier for a confident sample).
 */
private fun retentionCaption(
    retention30d: Double?,
    reviewCount: Int,
): String? =
    when {
        retention30d == null -> "no reviews yet · 30d"
        reviewCount < RETENTION_CONFIDENT_REVIEWS ->
            "from $reviewCount ${if (reviewCount == 1) "review" else "reviews"} · too few to read"
        else -> null
    }

@Composable
private fun HeadlineDivider() {
    Box(
        Modifier
            .width(1.dp)
            .height(HEADLINE_HEIGHT)
            .background(MaterialTheme.recallyColors.lineSoft),
    )
}

/** Section heading pair: `section` title on the left, caption qualifier on the right. */
@Composable
private fun SectionHeader(
    title: String,
    qualifier: String,
) {
    val colors = MaterialTheme.recallyColors
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = colors.ink)
        Text(qualifier, style = MaterialTheme.typography.labelMedium, color = colors.inkFaint)
    }
}

@Composable
private fun ForecastSection(forecast: List<ForecastBar>) {
    Column(verticalArrangement = Arrangement.spacedBy(RecallySpacing.md)) {
        SectionHeader(title = "Coming due", qualifier = "next 7 days")
        ForecastChart(forecast = forecast)
    }
}

/** Lapse rate by card type (artboard: Q&A `success`, Cloze `accent`). */
@Composable
private fun LapseRateByTypeSection(lapseRateByType: Map<String, Double>) {
    val colors = MaterialTheme.recallyColors
    val orderedRates = lapseRateByType.entries.sortedWith(compareBy({ typeOrder(it.key) }, { it.key }))
    Column(verticalArrangement = Arrangement.spacedBy(RecallySpacing.md)) {
        SectionHeader(title = "Lapse rate", qualifier = "by card type")
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(RecallyRadius.md))
                    .background(colors.surface)
                    .border(1.dp, colors.line, RoundedCornerShape(RecallyRadius.md))
                    .padding(RecallySpacing.cardPadding),
            verticalArrangement = Arrangement.spacedBy(RecallySpacing.cardPadding),
        ) {
            for ((type, rate) in orderedRates) {
                LapseRateRow(
                    label = typeLabel(type),
                    rate = rate,
                    color = typeColor(type),
                )
            }
            comparisonLine(orderedRates)?.let { comparison ->
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(colors.lineSoft),
                )
                Text(comparison, style = MaterialTheme.typography.labelMedium, color = colors.inkFaint)
            }
        }
    }
}

/** Lapse rate by Writer guidance version — built now, rendered only with more than one version. */
@Composable
private fun LapseRateByGuidanceVersionSection(versions: List<GuidanceVersionLapseRate>) {
    val colors = MaterialTheme.recallyColors
    Column(verticalArrangement = Arrangement.spacedBy(RecallySpacing.md)) {
        SectionHeader(title = "Lapse rate", qualifier = "by guidance version")
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(RecallyRadius.md))
                    .background(colors.surface)
                    .border(1.dp, colors.line, RoundedCornerShape(RecallyRadius.md))
                    .padding(RecallySpacing.cardPadding),
            verticalArrangement = Arrangement.spacedBy(RecallySpacing.cardPadding),
        ) {
            for (version in versions) {
                LapseRateRow(
                    label = "v${version.version}",
                    rate = version.lapseRate,
                    color = colors.primary,
                )
            }
        }
    }
}

/** One labelled lapse-rate row: name, percentage, and a `track` bar filled to the rate. */
@Composable
private fun LapseRateRow(
    label: String,
    rate: Double,
    color: Color,
) {
    val colors = MaterialTheme.recallyColors
    Column(verticalArrangement = Arrangement.spacedBy(RecallySpacing.sm)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(label, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, color = colors.ink)
            Text(
                "${(rate * 100).roundToInt()}%",
                style = MaterialTheme.typography.titleSmall,
                color = color,
            )
        }
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(LAPSE_BAR_HEIGHT)
                    .clip(RoundedCornerShape(RecallyRadius.sm))
                    .background(colors.track),
        ) {
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth(rate.toFloat().coerceIn(0f, 1f))
                        .height(LAPSE_BAR_HEIGHT)
                        .clip(RoundedCornerShape(RecallyRadius.sm))
                        .background(color),
            )
        }
    }
}

/** Display order: the documented types first, anything else after, alphabetically. */
private fun typeOrder(type: String): Int = DOCUMENTED_TYPE_ORDER.indexOf(type).let { if (it < 0) DOCUMENTED_TYPE_ORDER.size else it }

private fun typeLabel(type: String): String =
    when (type) {
        "qa" -> "Q&A"
        "cloze" -> "Cloze"
        else -> type.replaceFirstChar { it.uppercase() }
    }

@Composable
private fun typeColor(type: String): Color {
    val colors = MaterialTheme.recallyColors
    return when (type) {
        "qa" -> colors.success
        "cloze" -> colors.accent
        else -> colors.primary
    }
}

/** The artboard's one interpretation line, derived arithmetically from the served rates. */
private fun comparisonLine(orderedRates: List<Map.Entry<String, Double>>): String? {
    if (orderedRates.size < 2) return null
    val highest = orderedRates.maxBy { it.value }
    val lowest = orderedRates.minBy { it.value }
    if (highest.value == lowest.value) return null
    return "${typeLabel(highest.key)} cards lapse more often than ${typeLabel(lowest.key)}."
}

/** The no-data treatment (design-system.md, "States" → "No data for a metric"). */
private const val NO_DATA = "–"

/**
 * Reviews in the window below which the retention figure is qualified rather
 * than presented bare (design-system.md, "The retention figure").
 */
private const val RETENTION_CONFIDENT_REVIEWS = 20

private val DOCUMENTED_TYPE_ORDER = listOf("qa", "cloze")
private val HEADLINE_HEIGHT = 66.dp
private val LAPSE_BAR_HEIGHT = 7.dp

@CombinedPreviews
@Composable
private fun StatsScreenPreview() {
    val today = LocalDate.now()
    RecallyTheme {
        StatsScreen(
            uiState =
                StatsUiState(
                    streakDays = 9,
                    reviewsToday = 23,
                    retention30d = 0.87,
                    retentionReviewCount = 143,
                    forecast =
                        listOf(14, 9, 17, 6, 11, 4, 13).mapIndexed { offset, due ->
                            ForecastBar(date = today.plusDays(offset.toLong()), due = due, isToday = offset == 0)
                        },
                    lapseRateByType = mapOf("qa" to 0.11, "cloze" to 0.18),
                ),
            onRetry = {},
            onOpenSettings = {},
        )
    }
}

@CombinedPreviews
@Composable
private fun StatsScreenTwoVersionsPreview() {
    val today = LocalDate.now()
    RecallyTheme {
        StatsScreen(
            uiState =
                StatsUiState(
                    streakDays = 9,
                    reviewsToday = 23,
                    retention30d = 0.87,
                    retentionReviewCount = 143,
                    forecast =
                        listOf(14, 9, 17, 6, 11, 4, 13).mapIndexed { offset, due ->
                            ForecastBar(date = today.plusDays(offset.toLong()), due = due, isToday = offset == 0)
                        },
                    lapseRateByType = mapOf("qa" to 0.11, "cloze" to 0.18),
                    lapseRateByGuidanceVersion =
                        listOf(
                            GuidanceVersionLapseRate(1, 0.19),
                            GuidanceVersionLapseRate(2, 0.12),
                        ),
                ),
            onRetry = {},
            onOpenSettings = {},
        )
    }
}

@CombinedPreviews
@Composable
private fun StatsScreenEmptyPreview() {
    val today = LocalDate.now()
    RecallyTheme {
        // The fresh-install shape: zeros, seven zero bars, and a recall
        // figure that says "no reviews yet" rather than "0%" (issue #190).
        // Never an error (3e's test_stats_on_empty_database_returns_zeros_not_errors).
        StatsScreen(
            uiState =
                StatsUiState(
                    forecast =
                        (0L..6L).map { offset ->
                            ForecastBar(date = today.plusDays(offset), due = 0, isToday = offset == 0L)
                        },
                ),
            onRetry = {},
            onOpenSettings = {},
        )
    }
}

@CombinedPreviews
@Composable
private fun StatsScreenLoadingPreview() {
    RecallyTheme {
        StatsScreen(
            uiState = StatsUiState(isLoading = true),
            onRetry = {},
            onOpenSettings = {},
        )
    }
}

@CombinedPreviews
@Composable
private fun StatsScreenOfflinePreview() {
    RecallyTheme {
        StatsScreen(
            uiState = StatsUiState(isOffline = true),
            onRetry = {},
            onOpenSettings = {},
        )
    }
}

@CombinedPreviews
@Composable
private fun StatsScreenUnauthorizedPreview() {
    RecallyTheme {
        StatsScreen(
            uiState = StatsUiState(isUnauthorized = true),
            onRetry = {},
            onOpenSettings = {},
        )
    }
}
