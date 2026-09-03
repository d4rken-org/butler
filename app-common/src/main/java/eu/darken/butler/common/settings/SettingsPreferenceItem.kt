package eu.darken.butler.common.settings

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.Settings
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper

/**
 * A non-null [onUpgrade] gates the row while it is enabled: it gets the badge and every tap goes
 * to [onUpgrade] instead of [onClick]. A row with `enabled = false` shows no badge and keeps its
 * normal inert behavior even with an upgrade action, because upgrading would not make it usable.
 */
@Composable
fun SettingsPreferenceItem(
    icon: ImageVector,
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    value: String? = null,
    enabled: Boolean = true,
    onUpgrade: (() -> Unit)? = null,
) {
    val upgradeAction = onUpgrade?.takeIf { enabled }
    val contentAlpha = if (enabled) 1f else 0.5f

    SettingsBaseItem(
        icon = icon,
        title = title,
        onClick = upgradeAction ?: onClick,
        modifier = modifier,
        subtitle = subtitle,
        enabled = enabled,
        requiresUpgrade = upgradeAction != null,
        trailingContent = if (value != null) {
            {
                Text(
                    text = value,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f * contentAlpha),
                    modifier = Modifier.padding(start = 16.dp)
                )
            }
        } else null
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun SettingsPreferenceItemPreview() {
    SettingsPreferenceItem(
        icon = Icons.TwoTone.Settings,
        title = "Settings",
        subtitle = "General settings",
        onClick = {},
        modifier = Modifier,
        value = "Value"
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun SettingsPreferenceItemGatedPreview() {
    SettingsPreferenceItem(
        icon = Icons.TwoTone.Settings,
        title = "Settings",
        subtitle = "General settings",
        onClick = {},
        modifier = Modifier,
        value = "Value",
        onUpgrade = {},
    )
}
