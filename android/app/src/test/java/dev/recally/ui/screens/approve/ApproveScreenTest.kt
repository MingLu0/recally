package dev.recally.ui.screens.approve

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import dev.recally.domain.model.PendingCard
import dev.recally.ui.theme.RecallyTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Screen-level guard for issue #150: a `needs_human` card's CRITIC block must
 * never push the card's own Reject / Edit / Approve row off-screen. The
 * critique is a ~40-line wall here, matching the card that surfaced the bug
 * on the emulator. Phone-sized qualifiers keep the window realistic; without
 * the clamp the action row lands below the fold and LazyColumn never
 * composes it, so this test fails.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class ApproveScreenTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun test_action_row_is_reachable_with_a_long_critique() {
        val longCritique =
            (1..20).joinToString("\n\n") { round ->
                "Round $round: the Writer kept the term ambiguous, and the Critic asked for a sharper referent each time."
            }
        val card =
            PendingCard(
                id = 55,
                status = PendingCard.STATUS_NEEDS_HUMAN,
                type = PendingCard.TYPE_QA,
                front = "Why evaluate traces rather than individual steps?",
                back = "An LLM pipeline's behavior only makes sense end-to-end.",
                statusReason = longCritique,
                sourceHighlights = listOf("Traces capture the full execution path of an agent run."),
                truncated = false,
                bookId = 1,
                book = "Evals for AI Engineers",
                chapter = "3. Error Analysis",
            )

        composeTestRule.setContent {
            RecallyTheme {
                ApproveScreen(
                    uiState =
                        ApproveUiState(
                            groups = groupIntoChapters(listOf(card)),
                            pendingCount = 1,
                            isLoading = false,
                            filter = QueueFilter.NEEDS_YOU,
                        ),
                    onFilterChange = {},
                    onToggleHighlights = {},
                    onApproveCard = {},
                    onStartEdit = {},
                    onDismissEdit = {},
                    onEditCard = { _, _, _ -> },
                    onRejectCard = { _, _ -> },
                    onOpenSettings = {},
                    onNavigateBack = {},
                    onRetry = {},
                )
            }
        }

        // Reachable without touching the critique disclosure. The header's
        // "Approve" title is not clickable, so hasClickAction singles out the
        // card's action row. Asserted before the disclosure exists-check so a
        // regression fails on the buried row, the bug this guards.
        composeTestRule.onNode(hasText("Reject") and hasClickAction()).assertIsDisplayed()
        composeTestRule.onNode(hasText("Edit") and hasClickAction()).assertIsDisplayed()
        composeTestRule.onNode(hasText("Approve") and hasClickAction()).assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Expand critique").assertIsDisplayed()
    }
}
