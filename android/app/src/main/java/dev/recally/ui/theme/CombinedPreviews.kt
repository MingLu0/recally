package dev.recally.ui.theme

import android.content.res.Configuration.UI_MODE_NIGHT_YES
import androidx.compose.ui.tooling.preview.Preview

/**
 * The one multipreview annotation (docs/android.md, "Every previewable
 * composable has a preview"). Defined once so every screen and shared
 * component previews in the same configurations, and a change to the set is
 * made in a single place.
 */
@Preview(name = "light", showBackground = true)
@Preview(name = "dark", showBackground = true, uiMode = UI_MODE_NIGHT_YES)
@Preview(name = "font 2x", showBackground = true, fontScale = 2f)
annotation class CombinedPreviews
