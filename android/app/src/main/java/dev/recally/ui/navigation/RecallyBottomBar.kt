package dev.recally.ui.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.recally.ui.theme.CombinedPreviews
import dev.recally.ui.theme.RecallySpacing
import dev.recally.ui.theme.RecallyTheme
import dev.recally.ui.theme.recallyColors

/**
 * Bottom navigation (design-system.md, "Bottom navigation"): four items, a 1dp
 * `line-soft` top border, the active item's icon in a 34dp `primary` filled
 * circle with a white glyph, inactive icons 21dp `ink-faint` strokes.
 *
 * Pure composable — the selected route comes in, the tap goes out.
 */
@Composable
fun RecallyBottomBar(
    currentRoute: String?,
    onDestinationSelected: (BottomNavDestination) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.recallyColors
    Column(modifier = modifier.fillMaxWidth().background(colors.ground)) {
        HorizontalDivider(thickness = 1.dp, color = colors.lineSoft)
        Row(
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(top = RecallySpacing.sm, bottom = RecallySpacing.sm),
        ) {
            for (destination in BottomNavDestination.entries) {
                BottomNavItem(
                    destination = destination,
                    selected = destination.screen.route == currentRoute,
                    onClick = { onDestinationSelected(destination) },
                )
            }
        }
    }
}

@Composable
private fun BottomNavItem(
    destination: BottomNavDestination,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.recallyColors
    val label = stringResource(destination.labelRes)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(RecallySpacing.xs),
        modifier =
            Modifier
                .clickable(role = Role.Tab, onClick = onClick)
                .padding(horizontal = RecallySpacing.md, vertical = RecallySpacing.xs),
    ) {
        if (selected) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(34.dp).background(colors.primary, CircleShape),
            ) {
                Icon(
                    imageVector = destination.icon,
                    contentDescription = null,
                    tint = colors.ground,
                    modifier = Modifier.size(19.dp),
                )
            }
        } else {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(34.dp),
            ) {
                Icon(
                    imageVector = destination.icon,
                    contentDescription = null,
                    tint = colors.inkFaint,
                    modifier = Modifier.size(21.dp),
                )
            }
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            color = if (selected) colors.primary else colors.inkFaint,
        )
    }
}

@CombinedPreviews
@Composable
private fun RecallyBottomBarPreview() {
    RecallyTheme {
        RecallyBottomBar(
            currentRoute = Screen.Today.route,
            onDestinationSelected = {},
        )
    }
}
