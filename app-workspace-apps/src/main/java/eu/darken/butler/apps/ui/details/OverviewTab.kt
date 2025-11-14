package eu.darken.butler.apps.ui.details

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.twotone.Launch
import androidx.compose.material.icons.twotone.Android
import androidx.compose.material.icons.twotone.Block
import androidx.compose.material.icons.twotone.CheckCircle
import androidx.compose.material.icons.twotone.Delete
import androidx.compose.material.icons.twotone.FolderOpen
import androidx.compose.material.icons.twotone.GetApp
import androidx.compose.material.icons.twotone.Info
import androidx.compose.material.icons.twotone.Share
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import eu.darken.butler.apps.R
import eu.darken.butler.apps.core.details.AppDetailsWorkspace
import eu.darken.butler.apps.core.details.AppDetailsWorkspaceViewModel
import eu.darken.butler.common.formatFileSize

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun OverviewTab(
    state: AppDetailsWorkspace.State,
    vm: AppDetailsWorkspaceViewModel,
    modifier: Modifier = Modifier,
) {
    val app = state.app ?: return
    val context = LocalContext.current

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(top = 16.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Header with icon and label
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (app.icon != null) {
                AsyncImage(
                    model = app.icon.get(context),
                    contentDescription = null,
                    modifier = Modifier.size(64.dp),
                )
            } else {
                Icon(
                    imageVector = Icons.TwoTone.Android,
                    contentDescription = null,
                    modifier = Modifier.size(64.dp),
                )
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = app.label.get(context),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = app.packageName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        HorizontalDivider()

        // App Information
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = stringResource(R.string.apps_package_name_label),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = app.packageName,
                style = MaterialTheme.typography.bodyMedium,
            )

            if (app.versionName != null) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.apps_version_label),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "${app.versionName} (${app.versionCode})",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            if (app.appSize != null) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.apps_size_label),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = formatFileSize(app.appSize),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Type",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = if (app.isSystemApp) "System app" else "User app",
                style = MaterialTheme.typography.bodyMedium,
            )

            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Status",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = if (app.isEnabled) "Enabled" else "Disabled",
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        HorizontalDivider()

        // Storage Locations
        if (state.availablePaths.isNotEmpty()) {
            Text(
                text = stringResource(R.string.apps_details_storage_locations_label),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )

            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                state.availablePaths.forEach { appPath ->
                    FilledTonalButton(
                        onClick = {
                            vm.onBrowsePath(appPath.path)
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            imageVector = Icons.TwoTone.FolderOpen,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.size(8.dp))
                        Column(
                            modifier = Modifier.weight(1f),
                            horizontalAlignment = Alignment.Start
                        ) {
                            Text(
                                text = appPath.label.get(context),
                                style = MaterialTheme.typography.labelMedium,
                            )
                            Text(
                                text = appPath.path.path,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            HorizontalDivider()
        }

        // Quick Actions
        Text(
            text = "Actions",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )

        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Launch
            FilledTonalButton(
                onClick = { vm.onLaunchApp(app) }
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.TwoTone.Launch,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.size(8.dp))
                Text(stringResource(R.string.apps_action_launch))
            }

            // App Info
            FilledTonalButton(
                onClick = { vm.onShowAppInfo(app) }
            ) {
                Icon(
                    imageVector = Icons.TwoTone.Info,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.size(8.dp))
                Text(stringResource(R.string.apps_action_open_info))
            }

            // Enable/Disable
            if (app.isEnabled) {
                FilledTonalButton(
                    onClick = { vm.onEnableDisable(app) }
                ) {
                    Icon(
                        imageVector = Icons.TwoTone.Block,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.size(8.dp))
                    Text(stringResource(R.string.apps_action_disable))
                }
            } else {
                FilledTonalButton(
                    onClick = { vm.onEnableDisable(app) }
                ) {
                    Icon(
                        imageVector = Icons.TwoTone.CheckCircle,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.size(8.dp))
                    Text(stringResource(R.string.apps_action_enable))
                }
            }

            // Uninstall
            FilledTonalButton(
                onClick = { vm.onUninstall(app) }
            ) {
                Icon(
                    imageVector = Icons.TwoTone.Delete,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.size(8.dp))
                Text(stringResource(R.string.apps_action_uninstall))
            }

            // Export APK
            FilledTonalButton(
                onClick = { vm.onExportApk(app) }
            ) {
                Icon(
                    imageVector = Icons.TwoTone.GetApp,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.size(8.dp))
                Text(stringResource(R.string.apps_action_export_apk))
            }

            // Share
            FilledTonalButton(
                onClick = { vm.onShareApk(app) }
            ) {
                Icon(
                    imageVector = Icons.TwoTone.Share,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.size(8.dp))
                Text(stringResource(R.string.apps_action_share))
            }
        }
    }
}
