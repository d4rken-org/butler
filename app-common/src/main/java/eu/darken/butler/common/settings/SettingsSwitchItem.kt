package eu.darken.butler.common.settings

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.Settings
import androidx.compose.material3.Switch
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
 * to [onUpgrade] instead of toggling. A row with `enabled = false` shows no badge and keeps its
 * normal inert behavior even with an upgrade action, because upgrading would not make it usable.
 */
@Composable
fun SettingsSwitchItem(
    icon: ImageVector,
    title: String,
    subtitle: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onUpgrade: (() -> Unit)? = null,
) {
    val upgradeAction = onUpgrade?.takeIf { enabled }

    SettingsBaseItem(
        icon = icon,
        title = title,
        onClick = upgradeAction ?: { onCheckedChange(!checked) },
        modifier = modifier,
        subtitle = subtitle,
        enabled = enabled,
        requiresUpgrade = upgradeAction != null,
        trailingContent = {
            Switch(
                checked = checked,
                // A null callback drops the Switch's own toggleable node, leaving the row's
                // combinedClickable as the only place a tap can land. Pinned by the
                // isToggleable() assertions in SettingsUpgradeBadgeTest.
                onCheckedChange = if (upgradeAction != null) null else onCheckedChange,
                // Appearance only, this does not affect pointer input.
                enabled = enabled && upgradeAction == null,
                modifier = Modifier.padding(start = 16.dp)
            )
        }
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun SettingsSwitchItemPreview() {
    SettingsSwitchItem(
        icon = Icons.TwoTone.Settings,
        title = "Settings",
        subtitle = "General settings",
        checked = true,
        onCheckedChange = {},
        modifier = Modifier,
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun SettingsSwitchItemGatedPreview() {
    SettingsSwitchItem(
        icon = Icons.TwoTone.Settings,
        title = "Settings",
        subtitle = "General settings",
        checked = true,
        onCheckedChange = {},
        modifier = Modifier,
        onUpgrade = {},
    )
}
