package dev.recally.ui.screens.approve

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import dev.recally.domain.model.PendingCard
import dev.recally.shadows.ShadowMagnifier
import dev.recally.ui.theme.DarkRecallyColorTokens
import dev.recally.ui.theme.LightRecallyColorTokens
import dev.recally.ui.theme.RecallyTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Screen-level guards for the approval queue. The first test is the issue #150
 * regression guard: a `needs_human` card's CRITIC block must never push the
 * card's own Reject / Edit / Approve row off-screen. The critique is a ~40-line
 * wall here, matching the card that surfaced the bug on the emulator.
 *
 * The rest guard issue #227: the bulk approve bar is a floating action over
 * the queue, not a docked band — it must not consume layout height, must not
 * box itself in a four-sided border, and must not hide the queue's last card.
 *
 * Phone-sized qualifiers keep the window realistic; without the clamp the
 * action row lands below the fold and LazyColumn never composes it, so the
 * #150 test fails.
 *
 * The two colour tests render real pixels: `captureToImage()` cannot run here
 * (its window-capture path times out under Robolectric — see
 * [dev.recally.ui.screens.today.QueueCountTileTest]), so they draw the
 * compose root view into a software bitmap and sample it, in both light and
 * dark because the artboards' hairlines differ per theme.
 *
 * The two selection guards (issue #267) hold the card's read-only text to the
 * platform contract: long-press enters text-selection mode (asserted via the
 * selection context `SelectionContainer` installs — see the test for why the
 * semantics `TextSelectionRange` itself is not assertable under Robolectric)
 * and the wrapper must not swallow the disclosure toggle or the card actions.
 *
 * The issue #288 block guards the per-card decisions: neither Approve nor
 * Reject reaches its callback from the initial action tap — a confirmation
 * dialog sits in front of each, explaining the scheduling consequence (and
 * for Reject, collecting the optional Learner reason). The reject-dialog
 * tests carry a height-only qualifier to dodge robolectric#8460, the open
 * Robolectric freeze on a text field inside a dialog window wider than
 * 320dp — a test-runner workaround, not a production behaviour.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp", shadows = [ShadowMagnifier::class])
class ApproveScreenTest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

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

        setApproveContent(
            ApproveUiState(
                groups = groupIntoChapters(listOf(card)),
                pendingCount = 1,
                isLoading = false,
                filter = QueueFilter.NEEDS_YOU,
            ),
        )

        // Reachable without touching the critique disclosure. The header's
        // "Approve" title is not clickable, so hasClickAction singles out the
        // card's action row. Asserted before the disclosure exists-check so a
        // regression fails on the buried row, the bug this guards.
        composeTestRule.onNode(hasText("Reject") and hasClickAction()).assertIsDisplayed()
        composeTestRule.onNode(hasText("Edit") and hasClickAction()).assertIsDisplayed()
        composeTestRule.onNode(hasText("Approve") and hasClickAction()).assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Expand critique").assertIsDisplayed()
    }

    /**
     * Issue #267: the card's read-only text participates in the platform
     * text-selection UX — long-press is the entry point, served by a Compose
     * `SelectionContainer` around the card content.
     *
     * The ticket named the assertion "the text node carries a non-collapsed
     * `TextSelectionRange` in semantics". That is not observable under
     * Robolectric: the long-press gesture itself runs (the selection handlers
     * complete and consume the release), but the selection never materialises
     * — no `TextSelectionRange` appears in merged or unmerged semantics, no
     * selection toolbar is requested, on SDKs 27 and 34, with legacy and
     * native graphics, for stationary, jitter, drag and double-tap gestures.
     * Verified with a scratch `SelectionContainer { Text(...) }` repro: the
     * gap is in the framework's text hit-test/registrar propagation under
     * Robolectric, not in this screen. On device the interaction is the
     * platform default.
     *
     * What IS observable, and red on `main`: a `SelectionContainer` installs
     * a focusable selection root around its content (the selection manager's
     * `focusable()`, which also drives clear-selection-on-focus-loss). So this
     * test performs the ticket's long-press on the card text and asserts the
     * text sits under that focusable selection root — the marker that the
     * read-only content is inside the platform selection context. Without the
     * wrapper there is no focusable node anywhere in the card, so this fails
     * before the implementation exists.
     */
    @Test
    fun test_card_text_long_press_enters_selection_mode() {
        val card = queueCard(1)
        setApproveContent(queueState(listOf(card), bulkApprovableCount = null))

        composeTestRule
            .onNodeWithText(card.front)
            .assertIsDisplayed()
            .performTouchInput { longClick(durationMillis = 1000) }

        val focusableSelectionRoot =
            SemanticsMatcher("is a focusable selection root") { node ->
                SemanticsProperties.Focused in node.config
            }
        composeTestRule
            .onNode(
                hasText(card.front) and hasAnyAncestor(focusableSelectionRoot),
                useUnmergedTree = true,
            ).assertIsDisplayed()
    }

    /**
     * Issue #267: wrapping the card text in a `SelectionContainer` must not
     * swallow the interactions around it — the source-highlight disclosure
     * still toggles and the Approve action still raises its card-id callback.
     * Since issue #288 Approve is confirm-gated, so the wrapper must let the
     * confirmation dialog through too; confirming there is what fires.
     */
    @Test
    fun test_text_selection_preserves_card_actions_and_source_toggle() {
        val card = queueCard(1)
        var toggledCardId: Long? = null
        var approvedCardId: Long? = null
        setApproveContent(
            queueState(listOf(card), bulkApprovableCount = null),
            onToggleHighlights = { toggledCardId = it },
            onApproveCard = { approvedCardId = it },
        )

        composeTestRule.onNodeWithText("1 source highlight").performClick()
        assertEquals("disclosure tap must still toggle", card.id, toggledCardId)

        composeTestRule.onNode(approveActionInQueue()).performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNode(approveConfirmButton()).performClick()
        composeTestRule.waitForIdle()
        assertEquals("approve action must still fire", card.id, approvedCardId)
    }

    @Test
    fun test_inline_editor_shows_save_not_save_and_approve() {
        // Issue #287: saving and deciding are separate intents. The inline
        // editor's action is "Save" — it keeps the card queued — and the old
        // combined save-and-approve label is gone. The forbidden label is
        // assembled so the tree-wide grep for it (the ticket's Done-when
        // check) matches only a real regression, not this test.
        val combinedLabel = listOf("Save", "& approve").joinToString(" ")
        val card = queueCard(1)
        var state by mutableStateOf(queueState(listOf(card), bulkApprovableCount = null))
        setApproveContent(
            state,
            onStartEdit = { state = state.copy(editingCardId = it) },
            readState = { state },
        )

        composeTestRule.onNode(hasText("Edit") and hasClickAction()).performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText(combinedLabel).assertDoesNotExist()
        composeTestRule.onNode(hasText("Save") and hasClickAction()).assertIsDisplayed()
    }

    @Test
    fun test_save_callback_does_not_invoke_approve_callback() {
        // Negative: tapping Save in the inline editor emits only the save
        // callback — the approve callback never fires (issue #287).
        val card = queueCard(1)
        var state by mutableStateOf(queueState(listOf(card), bulkApprovableCount = null))
        val edits = mutableListOf<Triple<Long, String, String>>()
        var approvedCardId: Long? = null
        setApproveContent(
            state,
            onStartEdit = { state = state.copy(editingCardId = it) },
            onEditCard = { id, front, back ->
                edits += Triple(id, front, back)
                state = state.copy(editingCardId = null)
            },
            onApproveCard = { approvedCardId = it },
            readState = { state },
        )

        composeTestRule.onNode(hasText("Edit") and hasClickAction()).performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNode(hasText("Save") and hasClickAction()).performClick()
        composeTestRule.waitForIdle()

        assertEquals("save fired once with the card id and its text", listOf(Triple(card.id, card.front, card.back)), edits)
        assertNull("tapping Save must never invoke the approve callback (negative)", approvedCardId)
    }

    @Test
    fun test_source_numbering_restarts_for_each_card() {
        val firstCard =
            queueCard(1).copy(sourceHighlights = listOf("First alpha source.", "Second alpha source."))
        val secondCard =
            queueCard(2).copy(sourceHighlights = listOf("First beta source.", "Second beta source."))
        setApproveContent(
            ApproveUiState(
                groups = groupIntoChapters(listOf(firstCard, secondCard)),
                pendingCount = 2,
                isLoading = false,
                expandedHighlightCardIds = setOf(firstCard.id, secondCard.id),
            ),
        )

        // Each card numbers its own sources from 1 — no global numbering
        // across the queue. With two two-source cards expanded, "Source 1"
        // and "Source 2" each appear once per card and "Source 3" must not
        // exist; a global counter would number the second card's sources 3
        // and 4 instead.
        composeTestRule.onAllNodesWithText("Source 1").also { assertEquals(2, it.fetchSemanticsNodes().size) }
        composeTestRule.onAllNodesWithText("Source 2").also { assertEquals(2, it.fetchSemanticsNodes().size) }
        composeTestRule.onNodeWithText("Source 3").assertDoesNotExist()
    }

    @Test
    fun test_bulk_bar_does_not_reduce_the_queue_list_height() {
        val cards = (1L..6L).map(::queueCard)
        var bulkApprovableCount by mutableStateOf<Int?>(cards.size)
        setApproveContent(
            queueState(cards, bulkApprovableCount = cards.size),
            readState = { queueState(cards, bulkApprovableCount) },
        )

        val heightWithBar = queueListHeight()
        bulkApprovableCount = null
        composeTestRule.waitForIdle()
        val heightWithoutBar = queueListHeight()

        assertEquals(
            "the bulk bar floats over the queue; showing it must not take height from the list",
            heightWithoutBar,
            heightWithBar,
            0.5f,
        )
    }

    private fun queueListHeight(): Float {
        composeTestRule.onNodeWithTag(QUEUE_LIST_TAG).assertIsDisplayed()
        return composeTestRule
            .onNodeWithTag(QUEUE_LIST_TAG)
            .getBoundsInRoot()
            .height.value
    }

    @Test
    fun test_last_queue_card_is_reachable_with_the_bulk_bar_shown() {
        val cards = (1L..30L).map(::queueCard)
        var approvedCardId: Long? = null
        setApproveContent(
            queueState(cards, bulkApprovableCount = cards.size),
            onApproveCard = { approvedCardId = it },
        )

        // One chapter header + 30 cards; index 30 is the last card.
        composeTestRule.onNodeWithTag(QUEUE_LIST_TAG).performScrollToIndex(cards.size)

        val lastApproveAction =
            composeTestRule
                .onAllNodes(hasText("Approve") and hasClickAction())
                .onLast()
        lastApproveAction.assertIsDisplayed()

        // "Reachable" means above the floating bar, not merely within the
        // window: assertIsDisplayed alone cannot see the overlay.
        val barTop = composeTestRule.onNodeWithTag(BULK_APPROVE_BAR_TAG).getBoundsInRoot().top
        assertTrue(
            "the last card's Approve action must sit above the floating bulk bar, not behind it",
            lastApproveAction.getBoundsInRoot().bottom <= barTop,
        )

        // Issue #288: the action arms a confirmation dialog; confirming it is
        // what approves the card.
        lastApproveAction.performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNode(approveConfirmButton()).performClick()
        composeTestRule.waitForIdle()
        assertEquals(cards.last().id, approvedCardId)
    }

    @Test
    fun test_bulk_bar_paints_no_background_behind_the_pill() {
        var darkTheme by mutableStateOf(false)
        setApproveContent(
            queueState(listOf(queueCard(1)), bulkApprovableCount = 1),
            readDarkTheme = { darkTheme },
        )
        for (theme in listOf(false, true)) {
            darkTheme = theme
            composeTestRule.waitForIdle()
            val themeName = if (darkTheme) "dark" else "light"
            val frame = captureComposeRoot()
            val barBounds = composeTestRule.onNodeWithTag(BULK_APPROVE_BAR_TAG).getBoundsInRoot()
            val pillBounds = composeTestRule.onNodeWithTag(BULK_APPROVE_PILL_TAG).getBoundsInRoot()

            // Sanity anchor first: the pill centre really is `primary`,
            // proving the capture maps bounds to pixels.
            assertPixelApprox(
                "$themeName: pill centre",
                frame,
                pillBounds.centreX(),
                pillBounds.centreY(),
                if (darkTheme) DarkRecallyColorTokens.primary else LightRecallyColorTokens.primary,
            )

            // The strip beside the pill, at pill height — the bar's own
            // region. It must read as the page, not an opaque slab.
            val ground = if (darkTheme) DarkRecallyColorTokens.ground else LightRecallyColorTokens.ground
            val sampleX = ((barBounds.left.value + pillBounds.left.value) / 2f).toPx()
            assertPixelApprox(
                "$themeName: beside the pill",
                frame,
                sampleX,
                pillBounds.centreY(),
                ground,
            )
        }
    }

    @Test
    fun test_bulk_bar_has_no_border_on_its_bottom_edge() {
        var darkTheme by mutableStateOf(false)
        setApproveContent(
            queueState(listOf(queueCard(1)), bulkApprovableCount = 1),
            readDarkTheme = { darkTheme },
        )
        for (theme in listOf(false, true)) {
            darkTheme = theme
            composeTestRule.waitForIdle()
            val themeName = if (darkTheme) "dark" else "light"
            val frame = captureComposeRoot()
            val barBounds = composeTestRule.onNodeWithTag(BULK_APPROVE_BAR_TAG).getBoundsInRoot()
            val pillBounds = composeTestRule.onNodeWithTag(BULK_APPROVE_PILL_TAG).getBoundsInRoot()
            assertPixelApprox(
                "$themeName: pill centre",
                frame,
                pillBounds.centreX(),
                pillBounds.centreY(),
                if (darkTheme) DarkRecallyColorTokens.primary else LightRecallyColorTokens.primary,
            )

            // The bar's last pixel row, sampled across its width: a border
            // boxing the bar paints `lineSoft` here (the 1dp stroke hugs the
            // layout edge); a floating bar paints nothing, so the page
            // `ground` shows through.
            val ground = if (darkTheme) DarkRecallyColorTokens.ground else LightRecallyColorTokens.ground
            val bottomBandY = barBounds.bottom.value.toPx() - 1
            for (xFraction in listOf(0.25f, 0.5f, 0.75f)) {
                val sampleX = (barBounds.width.value * xFraction).toPx()
                assertPixelApprox(
                    "$themeName: bottom edge at ${(xFraction * 100).roundToInt()}% width",
                    frame,
                    sampleX,
                    bottomBandY,
                    ground,
                )
            }
        }
    }

    @Test
    fun test_bulk_bar_pill_stays_enabled_and_clickable_after_the_layout_change() {
        var bulkClicks = 0
        var state by mutableStateOf(queueState(listOf(queueCard(1)), bulkApprovableCount = 3))
        setApproveContent(
            state,
            onBulkApproveClick = { bulkClicks++ },
            readState = { state },
        )

        composeTestRule.onNodeWithTag(BULK_APPROVE_PILL_TAG).assertIsEnabled().performClick()
        assertEquals("onBulkApproveClick still fires", 1, bulkClicks)

        for (
        (caseName, disabledState) in
        listOf(
            "offline" to state.copy(isOffline = true),
            "bulk-approving" to state.copy(isBulkApproving = true),
            "a card busy" to state.copy(busyCardId = 1L),
        )
        ) {
            state = disabledState
            composeTestRule.waitForIdle()
            composeTestRule.onNodeWithTag(BULK_APPROVE_PILL_TAG).assertIsNotEnabled()
            bulkClicks = 0
            // Semantics clicks refuse disabled nodes, so assert the disabled
            // semantics itself is what guards the action.
            composeTestRule.onNodeWithTag(BULK_APPROVE_PILL_TAG).assertExists()
            assertEquals("$caseName: no approve-all fired", 0, bulkClicks)
        }
    }

    @Test
    fun test_pill_tap_shows_the_confirm_dialog_with_the_exact_count() {
        val cards = (1L..3L).map(::queueCard)
        var state by mutableStateOf(queueState(cards, bulkApprovableCount = cards.size))
        var confirmClicks = 0
        setApproveContent(
            state,
            onBulkApproveClick = { state = state.copy(showBulkApproveConfirm = true) },
            onConfirmBulkApprove = { confirmClicks++ },
            readState = { state },
        )

        composeTestRule.onNodeWithTag(BULK_APPROVE_PILL_TAG).performClick()
        composeTestRule.waitForIdle()

        // Issue #266: the dialog names the scale — the exact clean-card count
        // is what makes a stray tap stop and think.
        composeTestRule.onNodeWithTag(BULK_APPROVE_CONFIRM_DIALOG_TAG).assertIsDisplayed()
        composeTestRule.onNodeWithText("Approve all 3 cards?").assertIsDisplayed()
        assertEquals("the pill tap alone must never approve", 0, confirmClicks)
    }

    @Test
    fun test_cancel_dismisses_the_dialog_without_approving() {
        val cards = (1L..3L).map(::queueCard)
        var state by mutableStateOf(
            queueState(cards, bulkApprovableCount = cards.size).copy(showBulkApproveConfirm = true),
        )
        var confirmClicks = 0
        setApproveContent(
            state,
            onConfirmBulkApprove = { confirmClicks++ },
            onDismissBulkApproveConfirm = { state = state.copy(showBulkApproveConfirm = false) },
            readState = { state },
        )

        composeTestRule.onNodeWithText("Cancel").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag(BULK_APPROVE_CONFIRM_DIALOG_TAG).assertDoesNotExist()
        assertEquals("cancel must not approve", 0, confirmClicks)
        // The queue behind the dialog is untouched — every card still present.
        composeTestRule.onNodeWithTag(QUEUE_LIST_TAG).assertIsDisplayed()
        for (id in cards.map { it.id }) {
            composeTestRule.onNode(hasText("Question $id", substring = true)).assertExists()
        }
    }

    // --- Issue #288: individual approve and reject are confirmed and explained ---

    /**
     * Negative: the first tap on a card's Approve action arms the confirmation
     * dialog and emits no approve callback — for a `pending_review` card and
     * for an individually opened `needs_human` card alike.
     */
    @Test
    fun test_individual_approve_tap_shows_confirmation_without_approving() {
        val pendingCard = queueCard(1)
        val needsHumanCard = queueCard(2).copy(status = PendingCard.STATUS_NEEDS_HUMAN)
        val approvedIds = mutableListOf<Long>()
        setApproveContent(
            queueState(listOf(pendingCard, needsHumanCard), bulkApprovableCount = null),
            onApproveCard = { approvedIds += it },
        )

        // Both Approve actions live in the queue list; the dialog's own
        // Approve button is not a descendant of it.
        val approveActions = composeTestRule.onAllNodes(approveActionInQueue())
        assertEquals(2, approveActions.fetchSemanticsNodes().size)

        approveActions[0].performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag(APPROVE_CONFIRM_DIALOG_TAG).assertIsDisplayed()
        composeTestRule.onNodeWithText("Approve card?").assertIsDisplayed()
        assertEquals("the tap alone must never approve (negative)", emptyList<Long>(), approvedIds)

        composeTestRule.onNodeWithText("Cancel").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag(APPROVE_CONFIRM_DIALOG_TAG).assertDoesNotExist()

        composeTestRule.onAllNodes(approveActionInQueue())[1].performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag(APPROVE_CONFIRM_DIALOG_TAG).assertIsDisplayed()
        assertEquals("needs_human approve is gated the same way (negative)", emptyList<Long>(), approvedIds)
    }

    @Test
    fun test_approve_dialog_explains_that_card_enters_review_schedule() {
        val card = queueCard(1)
        setApproveContent(queueState(listOf(card), bulkApprovableCount = null))

        composeTestRule.onNode(approveActionInQueue()).performClick()
        composeTestRule.waitForIdle()

        // The consequence copy, not just the title and buttons.
        composeTestRule
            .onNodeWithText(
                "Approve this card and add it to your review schedule. " +
                    "You can edit or suspend it later from Decks.",
            ).assertIsDisplayed()
    }

    @Test
    fun test_individual_approve_confirm_calls_approve_once() {
        val card = queueCard(1)
        val approvedIds = mutableListOf<Long>()
        setApproveContent(
            queueState(listOf(card), bulkApprovableCount = null),
            onApproveCard = { approvedIds += it },
        )

        composeTestRule.onNode(approveActionInQueue()).performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag(APPROVE_CONFIRM_DIALOG_TAG).assertIsDisplayed()

        composeTestRule.onNode(approveConfirmButton()).performClick()
        composeTestRule.waitForIdle()

        assertEquals("confirm approves exactly once", listOf(card.id), approvedIds)
        composeTestRule.onNodeWithTag(APPROVE_CONFIRM_DIALOG_TAG).assertDoesNotExist()
    }

    /**
     * Negative: Cancel dismisses with no approve callback and the queue
     * untouched. (The literal scrim tap is not simulated: Robolectric wraps
     * the dialog window to its content bounds, so there is no in-window
     * backdrop to tap. Backdrop and back dismissal route through the same
     * `onDismissRequest` this dialog wires to its dismiss handler.)
     */
    @Test
    fun test_individual_approve_cancel_dismisses_without_approving() {
        val card = queueCard(1)
        val approvedIds = mutableListOf<Long>()
        setApproveContent(
            queueState(listOf(card), bulkApprovableCount = null),
            onApproveCard = { approvedIds += it },
        )

        composeTestRule.onNode(approveActionInQueue()).performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag(APPROVE_CONFIRM_DIALOG_TAG).assertIsDisplayed()

        // Cancel dismisses with no mutation and the queue untouched.
        composeTestRule.onNodeWithText("Cancel").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag(APPROVE_CONFIRM_DIALOG_TAG).assertDoesNotExist()
        assertEquals("cancel must not approve (negative)", emptyList<Long>(), approvedIds)
        composeTestRule.onNode(hasText("Question 1", substring = true)).assertIsDisplayed()
    }

    /**
     * Robolectric freezes a text field inside a platform-width dialog when the
     * window is wider than 320dp (robolectric/robolectric#8460 — still open),
     * so the reject-dialog tests, the only ones that compose the reason field,
     * drop the class's w411dp and keep the phone height. This is a
     * test-runner workaround; production is unaffected.
     */
    @Config(qualifiers = "h891dp")
    @Test
    fun test_reject_dialog_explains_rejection_and_keeps_reason_optional() {
        val card = queueCard(1)
        setApproveContent(queueState(listOf(card), bulkApprovableCount = null))

        composeTestRule.onNode(hasText("Reject") and hasClickAction()).performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag(REJECT_DIALOG_TAG).assertIsDisplayed()
        composeTestRule.onNodeWithText("Reject card?").assertIsDisplayed()
        composeTestRule
            .onNodeWithText(
                "The card will be marked rejected and removed from this queue. " +
                    "It will not enter your review schedule. " +
                    "Your optional reason helps the Learner improve future cards.",
            ).assertIsDisplayed()
        // The reason field remains optional — its label still says so, and
        // the blank-field confirm path is exercised in
        // test_reject_confirm_calls_reject_with_optional_reason.
        composeTestRule.onNodeWithText("Reason (optional — feeds the Learner)").assertIsDisplayed()
    }

    /** See [test_reject_dialog_explains_rejection_and_keeps_reason_optional] for the qualifier. */
    @Config(qualifiers = "h891dp")
    @Test
    fun test_reject_confirm_calls_reject_with_optional_reason() {
        val card = queueCard(1)
        val rejections = mutableListOf<Pair<Long, String?>>()
        setApproveContent(
            queueState(listOf(card), bulkApprovableCount = null),
            onRejectCard = { id, reason -> rejections += id to reason },
        )

        // An entered reason reaches the callback for the right card.
        composeTestRule.onNode(hasText("Reject") and hasClickAction()).performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Reason (optional — feeds the Learner)").performTextInput("too trivial")
        composeTestRule.onNode(rejectConfirmButton()).performClick()
        composeTestRule.waitForIdle()
        assertEquals(listOf(card.id to "too trivial"), rejections)

        // A blank field follows the existing null/no-reason path.
        composeTestRule.onNode(hasText("Reject") and hasClickAction()).performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNode(rejectConfirmButton()).performClick()
        composeTestRule.waitForIdle()
        assertEquals(listOf(card.id to "too trivial", card.id to null), rejections)
    }

    /** See [test_reject_dialog_explains_rejection_and_keeps_reason_optional] for the qualifier. */
    @Config(qualifiers = "h891dp")
    @Test
    fun test_reject_cancel_dismisses_without_rejecting() {
        val card = queueCard(1)
        val rejections = mutableListOf<Pair<Long, String?>>()
        setApproveContent(
            queueState(listOf(card), bulkApprovableCount = null),
            onRejectCard = { id, reason -> rejections += id to reason },
        )

        composeTestRule.onNode(hasText("Reject") and hasClickAction()).performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag(REJECT_DIALOG_TAG).assertIsDisplayed()
        composeTestRule.onNodeWithText("Reject card?").assertIsDisplayed()

        composeTestRule.onNodeWithText("Cancel").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag(REJECT_DIALOG_TAG).assertDoesNotExist()
        assertEquals("cancel must not reject (negative)", emptyList<Pair<Long, String?>>(), rejections)
        composeTestRule.onNode(hasText("Question 1", substring = true)).assertIsDisplayed()
    }

    // --- helpers ---

    private fun queueCard(id: Long): PendingCard =
        PendingCard(
            id = id,
            status = PendingCard.STATUS_PENDING_REVIEW,
            type = PendingCard.TYPE_QA,
            front = "Question $id: why evaluate traces rather than individual steps?",
            back = "Answer $id: an LLM pipeline's behavior only makes sense end-to-end.",
            statusReason = null,
            sourceHighlights = listOf("Traces capture the full execution path of an agent run ($id)."),
            truncated = false,
            bookId = 1,
            book = "Evals for AI Engineers",
            chapter = "3. Error Analysis",
        )

    private fun queueState(
        cards: List<PendingCard>,
        bulkApprovableCount: Int?,
    ) = ApproveUiState(
        groups = groupIntoChapters(cards),
        pendingCount = cards.size,
        isLoading = false,
        bulkApprovableCount = bulkApprovableCount,
    )

    /** A card's Approve action row — a descendant of the queue list, unlike the dialog's own Approve button. */
    private fun approveActionInQueue(): SemanticsMatcher =
        hasText("Approve") and hasClickAction() and hasAnyAncestor(hasTestTagValue(QUEUE_LIST_TAG))

    /** The individual-approve dialog's confirm button. */
    private fun approveConfirmButton(): SemanticsMatcher =
        hasText("Approve") and hasClickAction() and hasAnyAncestor(hasTestTagValue(APPROVE_CONFIRM_DIALOG_TAG))

    /** The reject dialog's danger-styled confirm button. */
    private fun rejectConfirmButton(): SemanticsMatcher =
        hasText("Reject") and hasClickAction() and hasAnyAncestor(hasTestTagValue(REJECT_DIALOG_TAG))

    private fun hasTestTagValue(tag: String): SemanticsMatcher = SemanticsMatcher.expectValue(SemanticsProperties.TestTag, tag)

    /**
     * Renders the screen the way production does: inside the app's Scaffold,
     * whose container paints the page `ground` (see AppScaffold). [readState]
     * and [readDarkTheme] let a test swap UiState or theme via snapshot vars —
     * `setContent` may only be called once per test.
     */
    private fun setApproveContent(
        uiState: ApproveUiState,
        darkTheme: Boolean = false,
        onToggleHighlights: (Long) -> Unit = {},
        onApproveCard: (Long) -> Unit = {},
        onStartEdit: (Long) -> Unit = {},
        onEditCard: (Long, String, String) -> Unit = { _, _, _ -> },
        onRejectCard: (Long, String?) -> Unit = { _, _ -> },
        onBulkApproveClick: () -> Unit = {},
        onConfirmBulkApprove: () -> Unit = {},
        onDismissBulkApproveConfirm: () -> Unit = {},
        readState: (() -> ApproveUiState)? = null,
        readDarkTheme: (() -> Boolean)? = null,
    ) {
        composeTestRule.setContent {
            RecallyTheme(darkTheme = readDarkTheme?.invoke() ?: darkTheme) {
                Scaffold { innerPadding ->
                    ApproveScreen(
                        uiState = readState?.invoke() ?: uiState,
                        onFilterChange = {},
                        onToggleHighlights = onToggleHighlights,
                        onApproveCard = onApproveCard,
                        onStartEdit = onStartEdit,
                        onDismissEdit = {},
                        onEditCard = onEditCard,
                        onRejectCard = onRejectCard,
                        onOpenSettings = {},
                        onNavigateBack = {},
                        onRetry = {},
                        onBulkApproveClick = onBulkApproveClick,
                        onConfirmBulkApprove = onConfirmBulkApprove,
                        onDismissBulkApproveConfirm = onDismissBulkApproveConfirm,
                        modifier = Modifier.padding(innerPadding),
                    )
                }
            }
        }
        composeTestRule.waitForIdle()
    }

    /**
     * Draws the compose root view into a software bitmap — the capture path
     * that works under Robolectric (see class doc). Coordinates match
     * `getBoundsInRoot()` once scaled by density.
     */
    private fun captureComposeRoot(): Bitmap {
        val view = composeTestRule.activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        return bitmap
    }

    private fun DpRect.centreX(): Int = ((left.value + right.value) / 2f).toPx()

    private fun DpRect.centreY(): Int = ((top.value + bottom.value) / 2f).toPx()

    private fun Float.toPx(): Int = (this * composeTestRule.density.density).roundToInt()

    private fun assertPixelApprox(
        what: String,
        bitmap: Bitmap,
        x: Int,
        y: Int,
        expected: Color,
    ) {
        val pixel = bitmap.getPixel(x, y)
        val expectedArgb = expected.toArgb()
        val channelsClose =
            listOf(16, 8, 0).all { shift ->
                abs(((pixel ushr shift) and 0xFF) - ((expectedArgb ushr shift) and 0xFF)) <= CHANNEL_TOLERANCE
            }
        assertTrue(
            "$what: expected #${Integer.toHexString(expectedArgb)} but was #${Integer.toHexString(pixel)} at ($x, $y)",
            android.graphics.Color.alpha(pixel) == 0xFF && channelsClose,
        )
    }

    private companion object {
        /** Per-channel tolerance for anti-aliasing; lineSoft vs ground differ by ≥ 0x0F per channel. */
        const val CHANNEL_TOLERANCE = 6
    }
}
