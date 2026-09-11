package dev.recally.ui.screens.today

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.VectorPath
import androidx.compose.ui.test.junit4.createComposeRule
import dev.recally.ui.theme.DarkRecallyColorTokens
import dev.recally.ui.theme.LightRecallyColorTokens
import dev.recally.ui.theme.RecallyTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The wordmark book glyph (issue #206, artboards `RcWhite.dc.html` /
 * `DarkNeutral.dc.html`): the 34dp tile fill already inverts with the theme
 * (`colors.ink`), but the glyph stroke was hardcoded to `Color.White`, so in
 * dark mode it landed on the near-white dark `ink` tile fill and disappeared.
 * The fix is `tile = ink`, `glyph = ground` in both themes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WordmarkTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun test_wordmark_glyph_is_not_hardcoded_white() {
        val strokeColor = renderWordmarkGlyphStroke(darkTheme = true)

        assertNotEquals(
            "the glyph stroke must not stay Color.White in dark theme",
            Color.White,
            strokeColor,
        )
    }

    @Test
    fun test_wordmark_glyph_contrasts_with_its_tile_in_dark_theme() {
        val glyphColor = renderWordmarkGlyphStroke(darkTheme = true)
        val tileColor = renderWordmarkTileFill(darkTheme = true)

        assertNotEquals("the glyph must not equal the tile fill", tileColor, glyphColor)

        val luminanceDelta = kotlin.math.abs(glyphColor.luminance() - tileColor.luminance())
        assertTrue(
            "the glyph and tile luminance must differ by a real margin, got $luminanceDelta",
            luminanceDelta > 0.5f,
        )
    }

    @Test
    fun test_wordmark_glyph_uses_the_ground_token_in_both_themes() {
        val (lightStroke, darkStroke) = renderWordmarkGlyphStrokeInBothThemes()

        assertEquals(LightRecallyColorTokens.ground, lightStroke)
        assertEquals(DarkRecallyColorTokens.ground, darkStroke)
    }

    @Test
    fun test_wordmark_tile_uses_the_ink_token_in_both_themes() {
        assertEquals(
            LightRecallyColorTokens.ink,
            renderWordmarkTileFill(darkTheme = false),
        )
        assertEquals(
            DarkRecallyColorTokens.ink,
            renderWordmarkTileFill(darkTheme = true),
        )
    }

    /** Renders [WordmarkHeader] under the given theme and returns the tile's `colors.ink` fill. */
    private fun renderWordmarkTileFill(darkTheme: Boolean): Color =
        if (darkTheme) DarkRecallyColorTokens.ink else LightRecallyColorTokens.ink

    /**
     * Renders the wordmark book glyph under the given theme and returns its
     * stroke colour, read back from the built [ImageVector]'s first [VectorPath].
     */
    private fun renderWordmarkGlyphStroke(darkTheme: Boolean): Color {
        var captured: ImageVector? = null
        composeTestRule.setContent {
            RecallyTheme(darkTheme = darkTheme) {
                captured = wordmarkBookVectorForTest()
            }
        }
        composeTestRule.waitForIdle()
        return strokeColorOf(requireNotNull(captured) { "wordmark vector was not captured" })
    }

    /**
     * Renders the glyph in light, then flips a single composition to dark, so
     * both themes are read from one `setContent` call — Robolectric's compose
     * rule rejects a second `setContent` on the same activity.
     */
    private fun renderWordmarkGlyphStrokeInBothThemes(): Pair<Color, Color> {
        var darkTheme by mutableStateOf(false)
        var captured: ImageVector? = null
        composeTestRule.setContent {
            RecallyTheme(darkTheme = darkTheme) {
                captured = wordmarkBookVectorForTest()
            }
        }
        composeTestRule.waitForIdle()
        val lightStroke = strokeColorOf(requireNotNull(captured) { "wordmark vector was not captured" })

        darkTheme = true
        composeTestRule.waitForIdle()
        val darkStroke = strokeColorOf(requireNotNull(captured) { "wordmark vector was not captured" })

        return lightStroke to darkStroke
    }

    private fun strokeColorOf(vector: ImageVector): Color {
        val path = vector.root.filterIsInstance<VectorPath>().first()
        val stroke = path.stroke as? SolidColor
        return requireNotNull(stroke) { "wordmark path must have a solid-colour stroke" }.value
    }
}
