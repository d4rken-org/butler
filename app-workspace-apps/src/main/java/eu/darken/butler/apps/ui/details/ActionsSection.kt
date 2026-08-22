package eu.darken.butler.apps.ui.details

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.twotone.Launch
import androidx.compose.material.icons.twotone.Block
import androidx.compose.material.icons.twotone.CheckCircle
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
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import androidx.compose.ui.unit.dp
import eu.darken.butler.apps.R
import eu.darken.butler.apps.core.details.AppInfo
import eu.darken.butler.common.compose.ButlerPreviewWrapper
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
        override val descriptionRes =
            if (isEnabled) R.string.apps_action_disable_desc else R.string.apps_action_enable_desc
    }

    data object ForceStop : AppAction() {
        override val icon = Icons.TwoTone.Stop
        override val titleRes = R.string.apps_action_force_stop
        override val descriptionRes = R.string.apps_action_force_stop_desc
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
    abstract val actions: List<AppAction>

    data object Primary : ActionGroup() {
        override val actions = listOf(AppAction.Launch, AppAction.OpenInfo)
    }

    data class Management(
        override val actions: List<AppAction>,
    ) : ActionGroup()

    data object Export : ActionGroup() {
        override val actions = listOf(AppAction.ExportApk, AppAction.ShareApk)
    }

    data class Destructive(override val actions: List<AppAction>) : ActionGroup()
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
    onClearData: () -> Unit = {},
    canEnableDisable: Boolean = true,
    canForceStop: Boolean = true,
    canClearData: Boolean = true,
) {
    if (app == null) return

    val managementActions = buildList {
        if (canEnableDisable) add(AppAction.EnableDisable(app.isEnabled))
        if (canForceStop) add(AppAction.ForceStop)
    }

    val destructiveActions = buildList {
        if (canClearData) add(AppAction.ClearData)
        add(AppAction.Uninstall)
    }

    val actionGroups = buildList {
        add(ActionGroup.Primary)
        if (managementActions.isNotEmpty()) add(ActionGroup.Management(managementActions))
        add(ActionGroup.Export)
        add(ActionGroup.Destructive(destructiveActions))
    }

    val actionHandlers = mapOf<AppAction, () -> Unit>(
        AppAction.Launch to onLaunchApp,
        AppAction.OpenInfo to onShowAppInfo,
        AppAction.EnableDisable(app.isEnabled) to onEnableDisable,
        AppAction.ForceStop to onForceStop,
        AppAction.ExportApk to onExportApk,
        AppAction.ShareApk to onShareApk,
        AppAction.ClearData to onClearData,
        AppAction.Uninstall to onUninstall,
    )

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        actionGroups.forEachIndexed { index, group ->
            ActionGroupSection(
                group = group,
                actionHandlers = actionHandlers,
                showDivider = index < actionGroups.size - 1,
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
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            thickness = 0.5.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
        )
    }
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
            .padding(horizontal = 16.dp, vertical = 8.dp),
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
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun ActionsSectionPreview() {
    ActionsSection(
        app = null,
        onLaunchApp = {},
        onShowAppInfo = {},
        onEnableDisable = {},
        onUninstall = {},
        onExportApk = {},
        onShareApk = {},
        onForceStop = {},
        onClearData = {},
    )
}
