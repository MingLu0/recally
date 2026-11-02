package dev.recally.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.testTag
import dev.recally.ui.theme.recallyColors

/**
 * Semantics tag marking a shimmer-driven loading placeholder (shared with
 * the screen tests — see ShimmerTest and the `test_loading_*_uses_shimmer`
 * gates in each screen's test class, issue #344).
 */
const val SHIMMER_TAG = "recallyShimmer"

/** One full sweep of the highlight band across a placeholder, in ms. */
internal const val SHIMMER_DURATION_MS = 1200

/**
 * How far the band's peak colour travels from the base `line-soft` toward
 * `ink-faint` — enough to read as motion against the near-white fill in
 * light and the dark fill in dark, without introducing a new colour token
 * (≈ `#C7C7C2` on light, `#575C5A` on dark).
 */
private const val HIGHLIGHT_FRACTION = 0.45f

/** The moving band's width as a fraction of the placeholder's width. */
private const val BAND_FRACTION = 0.5f

/**
 * Loading shimmer (design-system.md, "States → Loading"): the existing
 * `line-soft` skeleton fill, swept by a diagonal highlight band on a 1200ms
 * linear infinite loop. The modifier replaces only the static fill — apply
 * it where a placeholder previously drew `.background(colors.lineSoft)`,
 * keeping the surrounding `.clip()`/`.border()` chain untouched so every
 * placeholder keeps its existing shape, size and hairline.
 *
 * The band is drawn via `drawBehind`, so it inherits whatever clip the
 * caller's modifier chain already applies — clipped to each placeholder's
 * existing shape by construction.
 */
fun Modifier.shimmer(): Modifier =
    composed {
        val colors = MaterialTheme.recallyColors
        val transition = rememberInfiniteTransition(label = "shimmer")
        val progress by transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec =
                infiniteRepeatable(
                    animation = tween(durationMillis = SHIMMER_DURATION_MS, easing = LinearEasing),
                    repeatMode = RepeatMode.Restart,
                ),
            label = "shimmerSweep",
        )
        val base = colors.lineSoft
        val highlight = lerp(base, colors.inkFaint, HIGHLIGHT_FRACTION)

        Modifier
            .testTag(SHIMMER_TAG)
            .drawBehind {
                val bandWidth = size.width * BAND_FRACTION
                // progress 0 parks the band fully outside the start edge (the
                // placeholder reads as plain line-soft); progress 1 parks it
                // fully outside the end edge, then the loop restarts.
                val startX = -2f * bandWidth + (size.width + 2f * bandWidth) * progress
                drawRect(
                    brush =
                        Brush.linearGradient(
                            colors = listOf(base, highlight, base),
                            start = Offset(startX, 0f),
                            end = Offset(startX + 2f * bandWidth, size.height),
                            tileMode = TileMode.Clamp,
                        ),
                )
            }
    }
