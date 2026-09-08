package dev.recally.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * M3 ColorSchemes built from [LightRecallyColorTokens] / [DarkRecallyColorTokens]
 * per the token→role mapping in docs/design/design-system.md, "Colour".
 * Tokens with no M3 role are exposed via [MaterialTheme.recallyColors].
 */
val RecallyLightColorScheme: ColorScheme =
    lightColorScheme(
        // `background` paints the Scaffold container. Left unset, M3 defaults
        // it to a violet-tinted white (0xFFFFFBFE) that reads as pink against
        // our `ground`. The design has one page background, so both carry it.
        background = LightRecallyColorTokens.ground,
        onBackground = LightRecallyColorTokens.ink,
        surface = LightRecallyColorTokens.ground,
        surfaceContainer = LightRecallyColorTokens.surface,
        onSurface = LightRecallyColorTokens.ink,
        onSurfaceVariant = LightRecallyColorTokens.inkMuted,
        outline = LightRecallyColorTokens.inkFaint,
        outlineVariant = LightRecallyColorTokens.line,
        surfaceVariant = LightRecallyColorTokens.neutralWash,
        primary = LightRecallyColorTokens.primary,
        primaryContainer = LightRecallyColorTokens.primaryWash,
        tertiary = LightRecallyColorTokens.accent,
        error = LightRecallyColorTokens.danger,
        errorContainer = LightRecallyColorTokens.dangerWash,
    )

val RecallyDarkColorScheme: ColorScheme =
    darkColorScheme(
        background = DarkRecallyColorTokens.ground,
        onBackground = DarkRecallyColorTokens.ink,
        surface = DarkRecallyColorTokens.ground,
        surfaceContainer = DarkRecallyColorTokens.surface,
        onSurface = DarkRecallyColorTokens.ink,
        onSurfaceVariant = DarkRecallyColorTokens.inkMuted,
        outline = DarkRecallyColorTokens.inkFaint,
        outlineVariant = DarkRecallyColorTokens.line,
        surfaceVariant = DarkRecallyColorTokens.neutralWash,
        primary = DarkRecallyColorTokens.primary,
        primaryContainer = DarkRecallyColorTokens.primaryWash,
        tertiary = DarkRecallyColorTokens.accent,
        error = DarkRecallyColorTokens.danger,
        errorContainer = DarkRecallyColorTokens.dangerWash,
    )

/** Tokens beyond the M3 roles (ink-strong, line-soft, washes, …). */
val LocalRecallyColors = staticCompositionLocalOf { LightRecallyColorTokens }

val MaterialTheme.recallyColors: RecallyColorTokens
    @Composable
    @ReadOnlyComposable
    get() = LocalRecallyColors.current

/**
 * App theme. Follows the system theme (design-system.md, "Open decisions" 5 —
 * no in-app toggle).
 *
 * Elevation is never applied: separation is a hairline `line` border, never a
 * shadow, so no tonal elevation or shadow is introduced here.
 */
@Composable
fun RecallyTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) RecallyDarkColorScheme else RecallyLightColorScheme
    val recallyColors = if (darkTheme) DarkRecallyColorTokens else LightRecallyColorTokens

    androidx.compose.runtime.CompositionLocalProvider(
        LocalRecallyColors provides recallyColors,
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = RecallyTypography,
            content = content,
        )
    }
}
