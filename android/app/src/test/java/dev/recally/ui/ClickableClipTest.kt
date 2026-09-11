package dev.recally.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Issue #199: Compose clips a ripple to the shape set by `Modifier.clip()`,
 * not to the shape `Modifier.border()`/`Modifier.background()` draw. A chain
 * that declares a rounded border or fill and then `.clickable` with no
 * preceding `.clip()` gets a square ripple over a rounded card.
 *
 * These are source-level guards: reading the `.kt` file as a string is the
 * only way to assert modifier-chain *ordering* cheaply, without standing up
 * a full layout inspector. Every modifier chain in this codebase is written
 * as `modifier = <chain>` (confirmed across every screen file), so splitting
 * the file text on that literal token isolates one chain per Composable's
 * modifier parameter, and a generic check flags a rounded `.border`/
 * `.background` followed by `.clickable` with no intervening `.clip`.
 *
 * Two of the four sites cannot be caught by that generic rule and are
 * asserted by name instead, each scoped to its own function body (bounded by
 * the next `@Composable` marker) so the check cannot pass by finding an
 * unrelated `.clip(` elsewhere in the file:
 * - `StatsStrip`'s `ActionBar`: the rounding comes only from the `.clip()`
 *   this fix adds — its `.background(colors.primary)` is a plain fill with
 *   no `RoundedCornerShape` for the generic rule to key off, and the
 *   rounded branch lives inside a `.then(...)` block.
 * - `ApproveScreen`'s Reject cell: `ActionCell` itself has no `.border`/
 *   `.background` to key off (it is shared with the un-rounded Edit cell in
 *   the middle), so the clip is passed in from the `ActionRow` call site
 *   instead — checked there, scoped to the `ActionRow` function body.
 */
class ClickableClipTest {
    @Test
    fun test_rounded_clickable_surfaces_clip_before_indication() {
        val orderingViolations = findUnclippedRoundedClickables(FOUR_FIXED_SITES)
        assertTrue(
            "expected no unclipped rounded clickable chains in the fixed sites, found: $orderingViolations",
            orderingViolations.isEmpty(),
        )

        val actionBarBody = extractFunctionBody(STATS_STRIP.readText(), "ActionBar")
        assertTrue(
            "expected StatsStrip's ActionBar .then(...) branch to clip before .clickable(, it has no rounded " +
                "decoration for the generic ordering check to key off, so this is asserted by name",
            ACTION_BAR_CLIPS_BEFORE_CLICKABLE.containsMatchIn(actionBarBody),
        )

        val actionRowBody = extractFunctionBody(APPROVE_SCREEN.readText(), "ActionRow")
        assertTrue(
            "expected ApproveScreen's Reject ActionCell call to pass a .clip(...bottomStart...) modifier, " +
                "ActionCell has no rounded decoration to detect generically, so this is asserted by name",
            REJECT_CELL_CLIPS.containsMatchIn(actionRowBody),
        )
    }

    @Test
    fun test_no_rounded_card_is_clickable_without_a_clip() {
        val screenFiles =
            SCREENS_ROOT
                .walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .toList()
        assertTrue("expected to find screen source files under $SCREENS_ROOT", screenFiles.isNotEmpty())

        val violations = findUnclippedRoundedClickables(screenFiles)

        assertTrue(
            "found rounded clickable chains with no preceding .clip(), ripple will paint square corners: $violations",
            violations.isEmpty(),
        )
    }

    private companion object {
        val SCREENS_ROOT = File("src/main/java/dev/recally/ui/screens")
        val STATS_STRIP = File("src/main/java/dev/recally/ui/screens/today/StatsStrip.kt")
        val APPROVE_SCREEN = File("src/main/java/dev/recally/ui/screens/approve/ApproveScreen.kt")

        val FOUR_FIXED_SITES =
            listOf(
                File("src/main/java/dev/recally/ui/screens/today/TodayScreen.kt"),
                STATS_STRIP,
                APPROVE_SCREEN,
            )

        /** Every modifier chain in this codebase starts with this literal token. */
        const val CHAIN_START = "modifier ="

        /**
         * A rounded-shape declaration: `.border(...)` or `.background(...)`
         * whose argument list mentions `RoundedCornerShape`, matched
         * non-greedily up to the call's own closing paren so it does not
         * swallow the rest of the chain. Multi-line calls are common here, so
         * matching runs with [RegexOption.DOT_MATCHES_ALL].
         */
        val ROUNDED_DECORATION =
            Regex("""\.(border|background)\((?:[^()]|\([^()]*\))*RoundedCornerShape(?:[^()]|\([^()]*\))*\)""", RegexOption.DOT_MATCHES_ALL)
        val CLIP_CALL = Regex("""\.clip\(""")
        val CLICKABLE_CALL = Regex("""\.clickable\(""")

        /**
         * `ActionBar`'s `.then(...)` branch: `.background(colors.primary)`
         * then `.clickable(onClick = onStartReview)`, with `.clip(...)`
         * required to land before `.then(` (the shared modifier, per the
         * ticket, so the nothing-due branch keeps the same silhouette).
         */
        val ACTION_BAR_CLIPS_BEFORE_CLICKABLE =
            Regex(
                """\.clip\((?:[^()]|\([^()]*\))*\)\s*\.then\([\s\S]*?""" +
                    """\.background\(colors\.primary\)[\s\S]*?\.clickable\(onClick = onStartReview\)""",
            )

        /**
         * The Reject `ActionCell(...)` call inside `ActionRow`: its
         * `modifier` argument must clip to `bottomStart`, matching the
         * Approve cell (`:615`) that already clips to `bottomEnd`.
         */
        val REJECT_CELL_CLIPS =
            Regex(
                """label = "Reject"[\s\S]*?modifier = Modifier\.weight\(1f\)\.clip\(RoundedCornerShape\(bottomStart""",
            )

        data class Violation(
            val file: String,
            val snippet: String,
        )

        fun findUnclippedRoundedClickables(files: List<File>): List<Violation> {
            val violations = mutableListOf<Violation>()
            for (file in files) {
                if (!file.exists()) continue
                for (chain in splitIntoChains(file.readText())) {
                    val decorationMatch = ROUNDED_DECORATION.find(chain) ?: continue
                    val clickableMatch = CLICKABLE_CALL.find(chain, decorationMatch.range.last) ?: continue
                    val clip = CLIP_CALL.find(chain)
                    val clipsBeforeClickable = clip != null && clip.range.first < clickableMatch.range.first
                    if (!clipsBeforeClickable) {
                        violations.add(Violation(file.path, chain.take(250).replace(Regex("\\s+"), " ").trim()))
                    }
                }
            }
            return violations
        }

        /** One chain per `modifier =` occurrence, up to the next one (or EOF). */
        fun splitIntoChains(text: String): List<String> {
            val starts = Regex(Regex.escape(CHAIN_START)).findAll(text).map { it.range.first }.toList()
            return starts.mapIndexed { index, start ->
                val end = starts.getOrNull(index + 1) ?: text.length
                text.substring(start, end)
            }
        }

        /**
         * The body of the first `private fun <name>(` found, from its
         * preceding `@Composable` marker up to the next one — scoping a
         * name-based check to one function so it cannot match an unrelated
         * `.clip(` elsewhere in the file.
         */
        fun extractFunctionBody(
            fileText: String,
            functionName: String,
        ): String {
            val functionStart =
                Regex("""@Composable\s*\n(private\s+)?fun\s+$functionName\(""")
                    .find(fileText)
                    ?.range
                    ?.first
                    ?: error("function $functionName not found")
            // Anchored to line start so a `@Composable () -> Unit` lambda
            // *parameter type* inside this function's own signature does not
            // get mistaken for the next top-level declaration.
            val nextComposable = Regex("""^@Composable$""", RegexOption.MULTILINE).find(fileText, functionStart + 1)
            val functionEnd = nextComposable?.range?.first ?: fileText.length
            return fileText.substring(functionStart, functionEnd)
        }
    }
}
