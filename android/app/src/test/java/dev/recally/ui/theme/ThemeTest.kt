package dev.recally.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Theme gate for roadmap step 4a (issue #52). The source of truth is
 * docs/design/design-system.md — every assertion here names a documented
 * value, not a computed one.
 */
class ThemeTest {
    private data class TokenEntry(
        val name: String,
        val light: Color,
        val dark: Color,
    )

    private fun tokenEntries(): List<TokenEntry> {
        val light = LightRecallyColorTokens
        val dark = DarkRecallyColorTokens
        return listOf(
            TokenEntry("ground", light.ground, dark.ground),
            TokenEntry("surface", light.surface, dark.surface),
            TokenEntry("ink", light.ink, dark.ink),
            TokenEntry("ink-strong", light.inkStrong, dark.inkStrong),
            TokenEntry("ink-muted", light.inkMuted, dark.inkMuted),
            TokenEntry("ink-soft", light.inkSoft, dark.inkSoft),
            TokenEntry("ink-faint", light.inkFaint, dark.inkFaint),
            TokenEntry("line", light.line, dark.line),
            TokenEntry("line-soft", light.lineSoft, dark.lineSoft),
            TokenEntry("track", light.track, dark.track),
            TokenEntry("neutral-wash", light.neutralWash, dark.neutralWash),
            TokenEntry("primary-muted", light.primaryMuted, dark.primaryMuted),
            TokenEntry("primary", light.primary, dark.primary),
            TokenEntry("primary-dark", light.primaryDark, dark.primaryDark),
            TokenEntry("primary-wash", light.primaryWash, dark.primaryWash),
            TokenEntry("success", light.success, dark.success),
            TokenEntry("success-wash", light.successWash, dark.successWash),
            TokenEntry("warn", light.warn, dark.warn),
            TokenEntry("warn-wash", light.warnWash, dark.warnWash),
            TokenEntry("accent", light.accent, dark.accent),
            TokenEntry("danger", light.danger, dark.danger),
            TokenEntry("danger-wash", light.dangerWash, dark.dangerWash),
        )
    }

    @Test
    fun test_light_and_dark_schemes_define_every_token() {
        // Every token in the design-system table exists in both themes.
        val entries = tokenEntries()
        assertEquals(
            "design-system.md colour table has 22 tokens",
            22,
            entries.size,
        )
        for (entry in entries) {
            assertNotEquals("${entry.name} light is unspecified", Color.Unspecified, entry.light)
            assertNotEquals("${entry.name} dark is unspecified", Color.Unspecified, entry.dark)
        }

        // Every M3 role the design maps onto must carry our value, not a
        // Material default. Compared against the library defaults directly.
        val defaultLight = lightColorScheme()
        val defaultDark = darkColorScheme()

        val lightRoleValues =
            listOf(
                "surface" to RecallyLightColorScheme.surface,
                "surfaceContainer" to RecallyLightColorScheme.surfaceContainer,
                "onSurface" to RecallyLightColorScheme.onSurface,
                "onSurfaceVariant" to RecallyLightColorScheme.onSurfaceVariant,
                "outline" to RecallyLightColorScheme.outline,
                "outlineVariant" to RecallyLightColorScheme.outlineVariant,
                "surfaceVariant" to RecallyLightColorScheme.surfaceVariant,
                "primary" to RecallyLightColorScheme.primary,
                "primaryContainer" to RecallyLightColorScheme.primaryContainer,
                "tertiary" to RecallyLightColorScheme.tertiary,
                "error" to RecallyLightColorScheme.error,
                "errorContainer" to RecallyLightColorScheme.errorContainer,
            )
        val darkRoleValues =
            listOf(
                "surface" to RecallyDarkColorScheme.surface,
                "surfaceContainer" to RecallyDarkColorScheme.surfaceContainer,
                "onSurface" to RecallyDarkColorScheme.onSurface,
                "onSurfaceVariant" to RecallyDarkColorScheme.onSurfaceVariant,
                "outline" to RecallyDarkColorScheme.outline,
                "outlineVariant" to RecallyDarkColorScheme.outlineVariant,
                "surfaceVariant" to RecallyDarkColorScheme.surfaceVariant,
                "primary" to RecallyDarkColorScheme.primary,
                "primaryContainer" to RecallyDarkColorScheme.primaryContainer,
                "tertiary" to RecallyDarkColorScheme.tertiary,
                "error" to RecallyDarkColorScheme.error,
                "errorContainer" to RecallyDarkColorScheme.errorContainer,
            )

        for ((role, value) in lightRoleValues) {
            val default = lightRoleValue(defaultLight, role)
            assertNotEquals("light $role fell back to a Material default", default, value)
        }
        for ((role, value) in darkRoleValues) {
            val default = darkRoleValue(defaultDark, role)
            assertNotEquals("dark $role fell back to a Material default", default, value)
        }
    }

    /** WCAG relative luminance, sRGB. */
    private fun relativeLuminance(color: Color): Double {
        fun channel(c: Float): Double {
            val v = c.toDouble()
            return if (v <= 0.04045) v / 12.92 else Math.pow((v + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * channel(color.red) + 0.7152 * channel(color.green) + 0.0722 * channel(color.blue)
    }

    private fun lightRoleValue(
        scheme: androidx.compose.material3.ColorScheme,
        role: String,
    ): Color =
        when (role) {
            "surface" -> scheme.surface
            "surfaceContainer" -> scheme.surfaceContainer
            "onSurface" -> scheme.onSurface
            "onSurfaceVariant" -> scheme.onSurfaceVariant
            "outline" -> scheme.outline
            "outlineVariant" -> scheme.outlineVariant
            "surfaceVariant" -> scheme.surfaceVariant
            "primary" -> scheme.primary
            "primaryContainer" -> scheme.primaryContainer
            "tertiary" -> scheme.tertiary
            "error" -> scheme.error
            "errorContainer" -> scheme.errorContainer
            else -> error("unknown role $role")
        }

    private fun darkRoleValue(
        scheme: androidx.compose.material3.ColorScheme,
        role: String,
    ): Color = lightRoleValue(scheme, role)

    @Test
    fun test_dark_is_not_a_mechanical_inversion() {
        // Documented dark values (design-system.md, colour table) verbatim.
        assertEquals(Color(0xFF121413), DarkRecallyColorTokens.ground)
        assertEquals(Color(0xFF232725), DarkRecallyColorTokens.surface)

        // Dark is elevated: surface sits above ground, not below it.
        assertTrue(
            "dark surface must be lighter than dark ground",
            relativeLuminance(DarkRecallyColorTokens.surface) >
                relativeLuminance(DarkRecallyColorTokens.ground),
        )

        // Neither dark value is the light pair reused — dark is re-derived.
        assertEquals(Color(0xFFFFFFFF), LightRecallyColorTokens.ground)
        assertNotEquals(LightRecallyColorTokens.ground, DarkRecallyColorTokens.ground)
        assertNotEquals(LightRecallyColorTokens.surface, DarkRecallyColorTokens.surface)
    }

    @Test
    fun test_book_colour_is_stable_for_a_book_id() {
        val bookId = 42L
        assertEquals(
            bookCoverColor(bookId, darkTheme = false),
            bookCoverColor(bookId, darkTheme = false),
        )
        assertEquals(
            bookCoverColor(bookId, darkTheme = true),
            bookCoverColor(bookId, darkTheme = true),
        )

        // The seed list's entries differ, and two ids landing on different
        // entries produce different colours (light forest vs plum).
        assertNotEquals(
            bookCoverColor(1L, darkTheme = false),
            bookCoverColor(2L, darkTheme = false),
        )
        assertNotEquals(
            bookCoverColor(1L, darkTheme = true),
            bookCoverColor(2L, darkTheme = true),
        )
    }

    @Test
    fun test_type_scale_maps_every_m3_name() {
        // design-system.md type table: M3 name → size/line, weight.
        data class Expect(
            val size: Int,
            val line: Int,
            val weight: FontWeight,
        )

        val cases =
            mapOf(
                "headlineMedium" to (RecallyTypography.headlineMedium to Expect(27, 34, FontWeight.ExtraBold)),
                "titleLarge" to (RecallyTypography.titleLarge to Expect(22, 28, FontWeight.ExtraBold)),
                "titleSmall" to (RecallyTypography.titleSmall to Expect(15, 20, FontWeight.ExtraBold)),
                "headlineSmall" to (RecallyTypography.headlineSmall to Expect(24, 32, FontWeight.ExtraBold)),
                "titleMedium" to (RecallyTypography.titleMedium to Expect(21, 26, FontWeight.ExtraBold)),
                "bodyLarge" to (RecallyTypography.bodyLarge to Expect(17, 27, FontWeight.Normal)),
                "bodyMedium" to (RecallyTypography.bodyMedium to Expect(15, 22, FontWeight.Medium)),
                "bodySmall" to (RecallyTypography.bodySmall to Expect(14, 21, FontWeight.Normal)),
                "labelLarge" to (RecallyTypography.labelLarge to Expect(13, 18, FontWeight.SemiBold)),
                "labelMedium" to (RecallyTypography.labelMedium to Expect(12, 16, FontWeight.Normal)),
                "labelSmall" to (RecallyTypography.labelSmall to Expect(11, 14, FontWeight.Bold)),
            )

        for ((name, pair) in cases) {
            val (style, expect) = pair
            assertEquals("$name size", expect.size.sp, style.fontSize)
            assertEquals("$name line height", expect.line.sp, style.lineHeight)
            assertEquals("$name weight", expect.weight, style.fontWeight)
            assertNotEquals(
                "$name must use the app font family, not the platform default",
                FontFamily.Default,
                style.fontFamily,
            )
        }

        // metric-sm (19/24, 800) has no M3 name; it lives on the type-scale
        // extension, still never a raw sp at a call site.
        assertEquals(19.sp, RecallyTypeScale.metricSmall.fontSize)
        assertEquals(24.sp, RecallyTypeScale.metricSmall.lineHeight)
        assertEquals(FontWeight.ExtraBold, RecallyTypeScale.metricSmall.fontWeight)

        // Documented letter-spacing steps.
        assertEquals((-0.7).sp, RecallyTypography.titleLarge.letterSpacing)
        assertEquals((-0.7).sp, RecallyTypography.headlineSmall.letterSpacing)
        assertEquals((-0.4).sp, RecallyTypography.titleMedium.letterSpacing)
        assertEquals(0.4.sp, RecallyTypography.labelSmall.letterSpacing)
    }
}
