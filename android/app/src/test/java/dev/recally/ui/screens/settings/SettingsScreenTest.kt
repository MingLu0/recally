package dev.recally.ui.screens.settings

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import dev.recally.ui.theme.RecallyTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Settings REVIEWS section rows (issue #151): the queued-ratings row — the
 * only at-rest readout of the offline rating outbox — must be present under
 * REVIEWS per the Settings artboard (`docs/design/RcSettings.dc.html`).
 *
 * Compose-under-Robolectric, same infrastructure as ChapterHeaderTest (#146).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsScreenTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `queued ratings row is displayed`() {
        composeTestRule.setContent {
            RecallyTheme {
                SettingsScreen(
                    uiState =
                        SettingsUiState(
                            baseUrl = "http://192.168.1.42:8000",
                            isLoading = false,
                            appVersion = "0.1.0",
                        ),
                    onBaseUrlChange = {},
                    onApiKeyChange = {},
                    onToggleApiKeyVisibility = {},
                    onTestConnection = {},
                )
            }
        }

        composeTestRule.onNodeWithText("REVIEWS").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("Queued ratings").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("All synced").performScrollTo().assertIsDisplayed()
    }
}
