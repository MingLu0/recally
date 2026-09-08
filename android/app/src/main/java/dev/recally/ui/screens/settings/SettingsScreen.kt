package dev.recally.ui.screens.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import dev.recally.ui.theme.CombinedPreviews
import dev.recally.ui.theme.RecallyRadius
import dev.recally.ui.theme.RecallySpacing
import dev.recally.ui.theme.RecallyTheme
import dev.recally.ui.theme.recallyColors

/**
 * Settings screen (artboards `RcSettings.dc.html` / `DkSettings.dc.html`;
 * docs/android.md, "Connecting to the backend"). Pure composable: UiState in,
 * callbacks out — the ViewModel lives at the route entry.
 *
 * The push-window row is a read-only status row, never a toggle
 * (design-system.md, "States"): `PUSH_WINDOW` is a server env var and
 * `devices` carries no per-device preference.
 */
@Composable
fun SettingsScreen(
    uiState: SettingsUiState,
    onBaseUrlChange: (String) -> Unit,
    onApiKeyChange: (String) -> Unit,
    onToggleApiKeyVisibility: () -> Unit,
    onTestConnection: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        Text(
            text = "Settings",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier =
                Modifier.padding(
                    horizontal = RecallySpacing.screenPadding,
                    vertical = RecallySpacing.md,
                ),
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        Column(
            verticalArrangement = Arrangement.spacedBy(RecallySpacing.xl),
            modifier =
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(
                        horizontal = RecallySpacing.screenPadding,
                        vertical = RecallySpacing.lg,
                    ),
        ) {
            ConnectionSection(
                uiState = uiState,
                onBaseUrlChange = onBaseUrlChange,
                onApiKeyChange = onApiKeyChange,
                onToggleApiKeyVisibility = onToggleApiKeyVisibility,
                onTestConnection = onTestConnection,
            )
            ReviewsSection(pushWindow = uiState.pushWindow)
            AboutSection(appVersion = uiState.appVersion)
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.outline,
    )
}

@Composable
private fun ConnectionSection(
    uiState: SettingsUiState,
    onBaseUrlChange: (String) -> Unit,
    onApiKeyChange: (String) -> Unit,
    onToggleApiKeyVisibility: () -> Unit,
    onTestConnection: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(RecallySpacing.md)) {
        SectionLabel("CONNECTION")

        Column(verticalArrangement = Arrangement.spacedBy(RecallySpacing.xs)) {
            Text(
                text = "Backend URL",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = uiState.baseUrl,
                onValueChange = onBaseUrlChange,
                singleLine = true,
                placeholder = {
                    Text(
                        text = "http://192.168.1.42:8000",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                },
                textStyle = MaterialTheme.typography.bodyMedium,
                shape = RoundedCornerShape(RecallyRadius.md),
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Column(verticalArrangement = Arrangement.spacedBy(RecallySpacing.xs)) {
            Text(
                text = "API key",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = uiState.apiKey,
                onValueChange = onApiKeyChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium,
                visualTransformation =
                    if (uiState.isApiKeyVisible) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                trailingIcon = {
                    TextButton(onClick = onToggleApiKeyVisibility) {
                        Text(
                            text = if (uiState.isApiKeyVisible) "Hide" else "Show",
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                },
                shape = RoundedCornerShape(RecallyRadius.md),
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                text = "Encrypted with an Android Keystore key, stored in DataStore — never in the build.",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.outline,
            )
        }

        ConnectionTestResult(uiState.connectionTest)

        OutlinedButton(
            onClick = onTestConnection,
            enabled = uiState.connectionTest != ConnectionTestState.Testing,
            shape = RoundedCornerShape(RecallyRadius.md),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(50.dp),
        ) {
            Text(
                text =
                    if (uiState.connectionTest == ConnectionTestState.Testing) {
                        "Testing…"
                    } else {
                        "Test connection"
                    },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun ConnectionTestResult(state: ConnectionTestState) {
    val colors = MaterialTheme.recallyColors
    when (state) {
        ConnectionTestState.Idle, ConnectionTestState.Testing -> Unit
        is ConnectionTestState.Connected ->
            ResultRow(
                title = "Connected",
                message = state.message.orEmpty(),
                containerColor = colors.successWash,
                contentColor = colors.primary,
            )
        is ConnectionTestState.WrongKey ->
            ResultRow(
                title = "Couldn't authenticate",
                message = state.message,
                containerColor = colors.dangerWash,
                contentColor = colors.danger,
            )
        is ConnectionTestState.Unreachable ->
            ResultRow(
                title = "No answer",
                message = state.message,
                containerColor = colors.warnWash,
                contentColor = colors.warn,
            )
        is ConnectionTestState.HttpsRequired ->
            ResultRow(
                title = "HTTPS required",
                message = state.message,
                containerColor = colors.warnWash,
                contentColor = colors.warn,
            )
    }
}

@Composable
private fun ResultRow(
    title: String,
    message: String,
    containerColor: androidx.compose.ui.graphics.Color,
    contentColor: androidx.compose.ui.graphics.Color,
) {
    Surface(
        shape = RoundedCornerShape(RecallyRadius.md),
        color = containerColor,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(RecallySpacing.xs),
            modifier = Modifier.padding(RecallySpacing.cardPadding),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodySmall,
                color = contentColor,
            )
            Text(
                text = message,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ReviewsSection(pushWindow: String) {
    val colors = MaterialTheme.recallyColors
    Column(verticalArrangement = Arrangement.spacedBy(RecallySpacing.md)) {
        SectionLabel("REVIEWS")
        Surface(
            shape = RoundedCornerShape(RecallyRadius.md),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(RecallySpacing.md),
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(RecallySpacing.cardPadding),
                ) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(RecallySpacing.xs),
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(
                            text = "Daily reminder",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = "At most one push a day, $pushWindow",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                    Surface(
                        shape = RoundedCornerShape(RecallyRadius.sm),
                        color = colors.successWash,
                    ) {
                        Text(
                            text = "ON",
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.primary,
                            modifier =
                                Modifier.padding(
                                    horizontal = RecallySpacing.sm,
                                    vertical = RecallySpacing.xs,
                                ),
                        )
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Text(
                    text = "Set on the server (PUSH_WINDOW). Not changeable here.",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier =
                        Modifier.padding(
                            horizontal = RecallySpacing.cardPadding,
                            vertical = RecallySpacing.md,
                        ),
                )
            }
        }
    }
}

@Composable
private fun AboutSection(appVersion: String) {
    Column(verticalArrangement = Arrangement.spacedBy(RecallySpacing.md)) {
        SectionLabel("ABOUT")
        Surface(
            shape = RoundedCornerShape(RecallyRadius.md),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(RecallySpacing.cardPadding),
            ) {
                Text(
                    text = "Version",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = appVersion,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }
    }
}

private val sampleBaseState =
    SettingsUiState(
        baseUrl = "http://192.168.1.42:8000",
        apiKey = API_KEY_MASK,
        hasStoredApiKey = true,
        isLoading = false,
        appVersion = "0.1.0",
    )

@CombinedPreviews
@Composable
private fun SettingsScreenIdlePreview() {
    RecallyTheme {
        SettingsScreen(
            uiState = sampleBaseState,
            onBaseUrlChange = {},
            onApiKeyChange = {},
            onToggleApiKeyVisibility = {},
            onTestConnection = {},
        )
    }
}

@CombinedPreviews
@Composable
private fun SettingsScreenTestingPreview() {
    RecallyTheme {
        SettingsScreen(
            uiState = sampleBaseState.copy(connectionTest = ConnectionTestState.Testing),
            onBaseUrlChange = {},
            onApiKeyChange = {},
            onToggleApiKeyVisibility = {},
            onTestConnection = {},
        )
    }
}

@CombinedPreviews
@Composable
private fun SettingsScreenConnectedPreview() {
    RecallyTheme {
        SettingsScreen(
            uiState =
                sampleBaseState.copy(
                    connectionTest =
                        ConnectionTestState.Connected(
                            latencyMs = 42,
                            message = "Connected — responded in 42 ms",
                        ),
                ),
            onBaseUrlChange = {},
            onApiKeyChange = {},
            onToggleApiKeyVisibility = {},
            onTestConnection = {},
        )
    }
}

@CombinedPreviews
@Composable
private fun SettingsScreenWrongKeyPreview() {
    RecallyTheme {
        SettingsScreen(
            uiState =
                sampleBaseState.copy(
                    connectionTest =
                        ConnectionTestState.WrongKey(
                            "Couldn't authenticate — the API key is wrong. Check the key in Settings.",
                        ),
                ),
            onBaseUrlChange = {},
            onApiKeyChange = {},
            onToggleApiKeyVisibility = {},
            onTestConnection = {},
        )
    }
}

@CombinedPreviews
@Composable
private fun SettingsScreenUnreachablePreview() {
    RecallyTheme {
        SettingsScreen(
            uiState =
                sampleBaseState.copy(
                    connectionTest =
                        ConnectionTestState.Unreachable(
                            "No answer from the server — check the URL, that the backend is " +
                                "running, and that the phone is on the same network.",
                        ),
                ),
            onBaseUrlChange = {},
            onApiKeyChange = {},
            onToggleApiKeyVisibility = {},
            onTestConnection = {},
        )
    }
}

@CombinedPreviews
@Composable
private fun SettingsScreenHttpsRequiredPreview() {
    RecallyTheme {
        SettingsScreen(
            uiState =
                sampleBaseState.copy(
                    connectionTest =
                        ConnectionTestState.HttpsRequired(
                            "HTTPS required — the network security config blocked cleartext to " +
                                "this host before the request left the phone.",
                        ),
                ),
            onBaseUrlChange = {},
            onApiKeyChange = {},
            onToggleApiKeyVisibility = {},
            onTestConnection = {},
        )
    }
}
