package dev.recally.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshState
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import dev.recally.ui.theme.CombinedPreviews
import dev.recally.ui.theme.RecallyTheme

/** Test-only hook onto the pull-to-refresh indicator (issue #346). */
const val PULL_TO_REFRESH_INDICATOR_TAG = "pullToRefreshIndicator"

/**
 * The pull-to-refresh indicator for the resumable list screens (issue #346):
 * the Material 3 default, gated so it composes only while a pull or a
 * refresh is on screen. The M3 default keeps its indicator composed and
 * parked just off-screen when idle, and a node parked off-screen still reads
 * as "displayed" to the semantics framework — so the gate restates the
 * visible-only-while-active contract instead (design-system.md, *States* →
 * "Refreshing"). [PullToRefreshState.distanceFraction] is snapshot state, so
 * the gate tracks the pull gesture as it happens.
 */
@Composable
fun BoxScope.PullToRefreshIndicator(
    state: PullToRefreshState,
    isRefreshing: Boolean,
) {
    if (isRefreshing || state.distanceFraction > 0f) {
        PullToRefreshDefaults.Indicator(
            state = state,
            isRefreshing = isRefreshing,
            modifier = Modifier.align(Alignment.TopCenter).testTag(PULL_TO_REFRESH_INDICATOR_TAG),
        )
    }
}

@CombinedPreviews
@Composable
private fun PullToRefreshIndicatorRefreshingPreview() {
    RecallyTheme {
        val state = rememberPullToRefreshState()
        Box(Modifier.fillMaxSize()) {
            Text("Loaded content behind the indicator")
            PullToRefreshIndicator(state = state, isRefreshing = true)
        }
    }
}

@CombinedPreviews
@Composable
private fun PullToRefreshIndicatorIdlePreview() {
    RecallyTheme {
        val state = rememberPullToRefreshState()
        Box(Modifier.fillMaxSize()) {
            Text("Idle: the indicator composes nothing")
            PullToRefreshIndicator(state = state, isRefreshing = false)
        }
    }
}
