package dev.recally.ui.screens.review

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.recally.ui.theme.CombinedPreviews
import dev.recally.ui.theme.RecallyRadius
import dev.recally.ui.theme.RecallySpacing
import dev.recally.ui.theme.RecallyTheme
import dev.recally.ui.theme.recallyColors

/**
 * Session progress (docs/design/design-system.md, "Session progress"): one
 * 6dp pill bar — `success` for cards answered, `warn` for cards pending a
 * repeat — plus a "N left" count.
 *
 * The denominator is *current* remaining work (`doneCount + cardsLeft`), not
 * a fixed session size: same-session re-queueing means a 12-card session
 * produces more than 12 presentations, so a fixed bar would jump backwards or
 * silently drop the repeat (docs/android.md, *Screens → 2*). `cardsLeft`
 * already counts the repeats, so adding `toRepeatCount` to the denominator
 * would count each repeat twice (#148).
 */
@Composable
fun SessionProgress(
    doneCount: Int,
    toRepeatCount: Int,
    cardsLeft: Int,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.recallyColors
    val (doneWeight, repeatWeight) = sessionProgressFillWeights(doneCount, toRepeatCount, cardsLeft)
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(RecallySpacing.md),
    ) {
        Box(
            modifier =
                Modifier
                    .weight(1f)
                    .height(6.dp)
                    .clip(RoundedCornerShape(RecallyRadius.pill))
                    .background(colors.track)
                    .testTag("session_progress_track"),
        ) {
            Row(modifier = Modifier.fillMaxHeight()) {
                if (doneCount > 0) {
                    Box(
                        modifier =
                            Modifier
                                .weight(doneWeight)
                                .fillMaxHeight()
                                .background(colors.success)
                                .testTag("session_progress_done"),
                    )
                }
                if (toRepeatCount > 0) {
                    Box(
                        modifier =
                            Modifier
                                .weight(repeatWeight)
                                .fillMaxHeight()
                                .background(colors.warn)
                                .testTag("session_progress_repeat"),
                    )
                }
                val remainderWeight = (1f - doneWeight - repeatWeight).coerceAtLeast(0f)
                if (remainderWeight > 0f) {
                    Spacer(modifier = Modifier.weight(remainderWeight))
                }
            }
        }
        Text(
            text = "$cardsLeft left",
            style = MaterialTheme.typography.labelMedium,
            color = colors.inkFaint,
        )
    }
}

@CombinedPreviews
@Composable
private fun SessionProgressPreview() {
    RecallyTheme {
        SessionProgress(doneCount = 6, toRepeatCount = 1, cardsLeft = 5)
    }
}

/**
 * Bar-fill weights for the done and to-repeat segments, extracted so the
 * fraction is unit-testable without rendering the composable. The
 * denominator is the current remaining work: `doneCount + cardsLeft` —
 * `cardsLeft` already includes the repeats, so `toRepeatCount` must not be
 * added a second time (#148).
 */
internal fun sessionProgressFillWeights(
    doneCount: Int,
    toRepeatCount: Int,
    cardsLeft: Int,
): Pair<Float, Float> {
    val denominator = (doneCount + cardsLeft).coerceAtLeast(1)
    return (doneCount.toFloat() / denominator) to (toRepeatCount.toFloat() / denominator)
}
