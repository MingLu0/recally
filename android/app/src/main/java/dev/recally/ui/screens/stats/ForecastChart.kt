package dev.recally.ui.screens.stats

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
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
import dev.recally.ui.theme.RecallyColorTokens
import dev.recally.ui.theme.RecallyRadius
import dev.recally.ui.theme.RecallySpacing
import dev.recally.ui.theme.RecallyTheme
import dev.recally.ui.theme.recallyColors
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

/**
 * The "Coming due — next 7 days" chart (artboards `RcStats.dc.html` /
 * `DkStats.dc.html`). Seven bottom-aligned bars inside a bordered card:
 * today's bar is the one focal mark (`primary`), the other six are
 * `primary-muted` — non-focal data marks, never text (design-system.md,
 * "Colour"). Bar heights are proportional to the week's largest due count; a
 * day with no due cards is a zero-height bar, not a missing one.
 */
@Composable
internal fun ForecastChart(
    forecast: List<ForecastBar>,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.recallyColors
    val maxDue = forecast.maxOfOrNull { it.due } ?: 0
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(RecallyRadius.md))
                .background(colors.surface)
                .border(1.dp, colors.line, RoundedCornerShape(RecallyRadius.md))
                .padding(
                    start = RecallySpacing.cardPadding,
                    end = RecallySpacing.cardPadding,
                    top = RecallySpacing.lg,
                    bottom = RecallySpacing.md,
                ),
        verticalArrangement = Arrangement.spacedBy(RecallySpacing.sm),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().height(CHART_AREA_HEIGHT),
            horizontalArrangement = Arrangement.spacedBy(BAR_GAP),
            verticalAlignment = Alignment.Bottom,
        ) {
            for (bar in forecast) {
                ForecastBarColumn(
                    bar = bar,
                    maxDue = maxDue,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(colors.lineSoft),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(BAR_GAP),
        ) {
            for (bar in forecast) {
                Text(
                    text = if (bar.isToday) "Today" else weekdayLabel(bar.date),
                    style =
                        MaterialTheme.typography.labelSmall.copy(
                            fontWeight = if (bar.isToday) FontWeight.SemiBold else FontWeight.Normal,
                        ),
                    color = if (bar.isToday) colors.ink else colors.inkFaint,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun ForecastBarColumn(
    bar: ForecastBar,
    maxDue: Int,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.recallyColors
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(RecallySpacing.xs, Alignment.Bottom),
    ) {
        Text(
            text = bar.due.toString(),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = if (bar.isToday) colors.primary else colors.inkFaint,
        )
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(barHeight(bar.due, maxDue))
                    .clip(RoundedCornerShape(topStart = RecallyRadius.sm, topEnd = RecallyRadius.sm))
                    .background(forecastBarColor(bar.isToday, colors)),
        )
    }
}

/** The focal-bar rule as a pure function so it is unit-testable without a composition. */
internal fun forecastBarColor(
    isToday: Boolean,
    colors: RecallyColorTokens,
): Color = if (isToday) colors.primary else colors.primaryMuted

/** Proportional height against the week's largest due count; zero dues get a zero-height bar. */
private fun barHeight(
    due: Int,
    maxDue: Int,
) = if (maxDue <= 0) 0.dp else MAX_BAR_HEIGHT * (due.toFloat() / maxDue)

private fun weekdayLabel(date: LocalDate): String = date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())

private val CHART_AREA_HEIGHT = 108.dp
private val MAX_BAR_HEIGHT = 94.dp
private val BAR_GAP = 7.dp

@CombinedPreviews
@Composable
private fun ForecastChartPreview() {
    val today = LocalDate.now()
    RecallyTheme {
        ForecastChart(
            forecast =
                listOf(14, 9, 17, 6, 11, 0, 13).mapIndexed { offset, due ->
                    ForecastBar(date = today.plusDays(offset.toLong()), due = due, isToday = offset == 0)
                },
        )
    }
}
