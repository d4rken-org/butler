package eu.darken.butler.apps.ui.details

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import eu.darken.butler.apps.R
import eu.darken.butler.apps.core.details.AppInfo
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper

@Composable
fun QuickActionsBar(
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

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.Start),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Launch
        IconButton(
            onClick = onLaunchApp,
            modifier = Modifier.size(40.dp)
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.TwoTone.Launch,
                contentDescription = stringResource(R.string.apps_action_launch),
                tint = MaterialTheme.colorScheme.primary
            )
        }

        // App Info
        IconButton(
            onClick = onShowAppInfo,
            modifier = Modifier.size(40.dp)
        ) {
            Icon(
                imageVector = Icons.TwoTone.Info,
                contentDescription = stringResource(R.string.apps_action_open_info),
                tint = MaterialTheme.colorScheme.primary
            )
        }

        // Enable/Disable
        IconButton(
            onClick = onEnableDisable,
            modifier = Modifier.size(40.dp)
        ) {
            Icon(
                imageVector = if (app.isEnabled) Icons.TwoTone.Block else Icons.TwoTone.CheckCircle,
                contentDescription = if (app.isEnabled) {
                    stringResource(R.string.apps_action_disable)
                } else {
                    stringResource(R.string.apps_action_enable)
                },
                tint = MaterialTheme.colorScheme.primary
            )
        }

        // Uninstall
        IconButton(
            onClick = onUninstall,
            modifier = Modifier.size(40.dp)
        ) {
            Icon(
                imageVector = Icons.TwoTone.Delete,
                contentDescription = stringResource(R.string.apps_action_uninstall),
                tint = MaterialTheme.colorScheme.primary
            )
        }

        // Export APK
        IconButton(
            onClick = onExportApk,
            modifier = Modifier.size(40.dp)
        ) {
            Icon(
                imageVector = Icons.TwoTone.GetApp,
                contentDescription = stringResource(R.string.apps_action_export_apk),
                tint = MaterialTheme.colorScheme.primary
            )
        }

        // Share
        IconButton(
            onClick = onShareApk,
            modifier = Modifier.size(40.dp)
        ) {
            Icon(
                imageVector = Icons.TwoTone.Share,
                contentDescription = stringResource(R.string.apps_action_share),
                tint = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Preview2
@Composable
private fun QuickActionsBarPreview() {
    PreviewWrapper {
        QuickActionsBar(
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
