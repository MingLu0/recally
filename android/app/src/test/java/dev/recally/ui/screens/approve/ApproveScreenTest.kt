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

        composeTestRule.onNode(hasText("Approve") and hasClickAction()).performClick()
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

        lastApproveAction.performClick()
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
                        onRejectCard = { _, _ -> },
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
