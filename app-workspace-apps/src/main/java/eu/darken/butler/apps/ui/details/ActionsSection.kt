package eu.darken.butler.apps.ui.details

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.twotone.Launch
import androidx.compose.material.icons.twotone.Block
import androidx.compose.material.icons.twotone.CheckCircle
import androidx.compose.material.icons.twotone.ClearAll
import androidx.compose.material.icons.twotone.Delete
import androidx.compose.material.icons.twotone.DeleteForever
import androidx.compose.material.icons.twotone.GetApp
import androidx.compose.material.icons.twotone.Info
import androidx.compose.material.icons.twotone.Share
import androidx.compose.material.icons.twotone.Stop
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.darken.butler.apps.R
import eu.darken.butler.apps.core.details.AppInfo
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper

// Sealed class for app actions - more idiomatic Kotlin
sealed class AppAction {
    abstract val icon: ImageVector
    abstract val titleRes: Int
    abstract val descriptionRes: Int

    // Primary actions
    data object Launch : AppAction() {
        override val icon = Icons.AutoMirrored.TwoTone.Launch
        override val titleRes = R.string.apps_action_launch
        override val descriptionRes = R.string.apps_action_launch_desc
    }

    data object OpenInfo : AppAction() {
        override val icon = Icons.TwoTone.Info
        override val titleRes = R.string.apps_action_open_info
        override val descriptionRes = R.string.apps_action_open_info_desc
    }

    // Management actions
    data class EnableDisable(val isEnabled: Boolean) : AppAction() {
        override val icon = if (isEnabled) Icons.TwoTone.Block else Icons.TwoTone.CheckCircle
        override val titleRes = if (isEnabled) R.string.apps_action_disable else R.string.apps_action_enable
        override val descriptionRes = if (isEnabled) R.string.apps_action_disable_desc else R.string.apps_action_enable_desc
    }

    data object ForceStop : AppAction() {
        override val icon = Icons.TwoTone.Stop
        override val titleRes = R.string.apps_action_force_stop
        override val descriptionRes = R.string.apps_action_force_stop_desc
    }

    data object ClearCache : AppAction() {
        override val icon = Icons.TwoTone.ClearAll
        override val titleRes = R.string.apps_action_clear_cache
        override val descriptionRes = R.string.apps_action_clear_cache_desc
    }

    // Export actions
    data object ExportApk : AppAction() {
        override val icon = Icons.TwoTone.GetApp
        override val titleRes = R.string.apps_action_export_apk
        override val descriptionRes = R.string.apps_action_export_apk_desc
    }

    data object ShareApk : AppAction() {
        override val icon = Icons.TwoTone.Share
        override val titleRes = R.string.apps_action_share
        override val descriptionRes = R.string.apps_action_share_desc
    }

    // Destructive actions
    data object ClearData : AppAction() {
        override val icon = Icons.TwoTone.DeleteForever
        override val titleRes = R.string.apps_action_clear_data
        override val descriptionRes = R.string.apps_action_clear_data_desc
    }

    data object Uninstall : AppAction() {
        override val icon = Icons.TwoTone.Delete
        override val titleRes = R.string.apps_action_uninstall
        override val descriptionRes = R.string.apps_action_uninstall_desc
    }
}

sealed class ActionGroup {
    abstract val titleRes: Int
    abstract val actions: List<AppAction>
    abstract val color: @Composable () -> Color

    data object Primary : ActionGroup() {
        override val titleRes = R.string.apps_actions_group_primary
        override val actions = listOf(AppAction.Launch, AppAction.OpenInfo)
        override val color: @Composable () -> Color = { MaterialTheme.colorScheme.primary }
    }

    data class Management(val isEnabled: Boolean) : ActionGroup() {
        override val titleRes = R.string.apps_actions_group_management
        override val actions = listOf(
            AppAction.EnableDisable(isEnabled),
            AppAction.ForceStop,
            AppAction.ClearCache
        )
        override val color: @Composable () -> Color = { MaterialTheme.colorScheme.primary }
    }

    data object Export : ActionGroup() {
        override val titleRes = R.string.apps_actions_group_export
        override val actions = listOf(AppAction.ExportApk, AppAction.ShareApk)
        override val color: @Composable () -> Color = { MaterialTheme.colorScheme.primary }
    }

    data object Destructive : ActionGroup() {
        override val titleRes = R.string.apps_actions_group_destructive
        override val actions = listOf(AppAction.ClearData, AppAction.Uninstall)
        override val color: @Composable () -> Color = { MaterialTheme.colorScheme.error }
    }
}

@Composable
fun ActionsSection(
    modifier: Modifier = Modifier,
    app: AppInfo?,
    onLaunchApp: () -> Unit,
    onShowAppInfo: () -> Unit,
    onEnableDisable: () -> Unit,
    onUninstall: () -> Unit,
    onExportApk: () -> Unit,
    onShareApk: () -> Unit,
    onForceStop: () -> Unit = {},
    onClearCache: () -> Unit = {},
    onClearData: () -> Unit = {},
) {
    if (app == null) return

    val actionGroups = listOf(
        ActionGroup.Primary,
        ActionGroup.Management(app.isEnabled),
        ActionGroup.Export,
        ActionGroup.Destructive
    )

    val actionHandlers = mapOf<AppAction, () -> Unit>(
        AppAction.Launch to onLaunchApp,
        AppAction.OpenInfo to onShowAppInfo,
        AppAction.EnableDisable(app.isEnabled) to onEnableDisable,
        AppAction.ForceStop to onForceStop,
        AppAction.ClearCache to onClearCache,
        AppAction.ExportApk to onExportApk,
        AppAction.ShareApk to onShareApk,
        AppAction.ClearData to onClearData,
        AppAction.Uninstall to onUninstall
    )

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        actionGroups.forEachIndexed { index, group ->
            ActionGroupSection(
                group = group,
                actionHandlers = actionHandlers,
                showDivider = index < actionGroups.size - 1
            )
        }
    }
}

@Composable
private fun ActionGroupSection(
    group: ActionGroup,
    actionHandlers: Map<AppAction, () -> Unit>,
    showDivider: Boolean,
) {
    ActionGroupHeader(
        title = stringResource(group.titleRes),
        color = group.color()
    )

    group.actions.forEach { action ->
        val isDestructive = group is ActionGroup.Destructive
        ActionItem(
            icon = action.icon,
            title = stringResource(action.titleRes),
            description = stringResource(action.descriptionRes),
            onClick = actionHandlers[action] ?: {},
            tintColor = if (isDestructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
        )
    }

    if (showDivider) {
        HorizontalDivider(
            modifier = Modifier.padding(vertical = 4.dp),
            thickness = 0.5.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
        )
    }
}

@Composable
private fun ActionGroupHeader(
    title: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
) {
    Text(
        text = title.uppercase(),
        style = MaterialTheme.typography.labelSmall.copy(
            fontSize = 11.sp,
            letterSpacing = 0.5.sp,
            fontWeight = FontWeight.Medium
        ),
        color = color.copy(alpha = 0.8f),
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 6.dp)
    )
}

@Composable
private fun ActionItem(
    icon: ImageVector,
    title: String,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tintColor: Color = MaterialTheme.colorScheme.primary,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = title,
            tint = tintColor,
            modifier = Modifier.size(24.dp)
        )

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (tintColor == MaterialTheme.colorScheme.error) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurface
                }
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Preview2
@Composable
private fun ActionsSectionPreview() {
    PreviewWrapper {
        ActionsSection(
            app = null,
            onLaunchApp = {},
            onShowAppInfo = {},
            onEnableDisable = {},
            onUninstall = {},
            onExportApk = {},
            onShareApk = {},
            onForceStop = {},
            onClearCache = {},
            onClearData = {},
        )
    }
}
