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
import androidx.compose.material.icons.twotone.GetApp
import androidx.compose.material.icons.twotone.Info
import androidx.compose.material.icons.twotone.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import eu.darken.butler.apps.R
import eu.darken.butler.apps.core.details.AppInfo
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper

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
) {
    if (app == null) return

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        ActionItem(
            icon = Icons.AutoMirrored.TwoTone.Launch,
            title = stringResource(R.string.apps_action_launch),
            description = stringResource(R.string.apps_action_launch_desc),
            onClick = onLaunchApp
        )

        ActionItem(
            icon = Icons.TwoTone.Info,
            title = stringResource(R.string.apps_action_open_info),
            description = stringResource(R.string.apps_action_open_info_desc),
            onClick = onShowAppInfo
        )

        ActionItem(
            icon = if (app.isEnabled) Icons.TwoTone.Block else Icons.TwoTone.CheckCircle,
            title = if (app.isEnabled) {
                stringResource(R.string.apps_action_disable)
            } else {
                stringResource(R.string.apps_action_enable)
            },
            description = if (app.isEnabled) {
                stringResource(R.string.apps_action_disable_desc)
            } else {
                stringResource(R.string.apps_action_enable_desc)
            },
            onClick = onEnableDisable
        )

        ActionItem(
            icon = Icons.TwoTone.Delete,
            title = stringResource(R.string.apps_action_uninstall),
            description = stringResource(R.string.apps_action_uninstall_desc),
            onClick = onUninstall
        )

        ActionItem(
            icon = Icons.TwoTone.GetApp,
            title = stringResource(R.string.apps_action_export_apk),
            description = stringResource(R.string.apps_action_export_apk_desc),
            onClick = onExportApk
        )

        ActionItem(
            icon = Icons.TwoTone.Share,
            title = stringResource(R.string.apps_action_share),
            description = stringResource(R.string.apps_action_share_desc),
            onClick = onShareApk
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
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(24.dp)
        )

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
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
            onShareApk = {}
        )
    }
}
