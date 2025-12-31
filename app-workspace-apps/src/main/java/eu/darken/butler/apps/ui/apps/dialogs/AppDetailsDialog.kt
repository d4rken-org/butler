package eu.darken.butler.apps.ui.apps.dialogs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import eu.darken.butler.apps.R
import eu.darken.butler.apps.core.AppPath
import eu.darken.butler.apps.core.engine.AppItem
import eu.darken.butler.apps.ui.apps.AppsActionBarItem
import eu.darken.butler.common.formatFileSize
import eu.darken.butler.workspace.ui.bottomsheet.PaneScopedBottomSheet

@Composable
fun AppDetailsDialog(
    app: AppItem,
    availablePaths: List<AppPath>,
    onDismiss: () -> Unit,
    onAction: (AppsActionBarItem) -> Unit,
    modifier: Modifier = Modifier,
    bottomInset: Dp = 0.dp,
) {
    PaneScopedBottomSheet(
        visible = true,
        onDismiss = onDismiss,
        bottomInset = bottomInset,
        modifier = modifier,
    ) {
        AppDetailsContent(
            app = app,
            availablePaths = availablePaths,
            onAction = onAction,
            onDismiss = onDismiss,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AppDetailsContent(
    app: AppItem,
    availablePaths: List<AppPath>,
    onAction: (AppsActionBarItem) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(bottom = 32.dp),
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
        if (availablePaths.isNotEmpty()) {
            Text(
                text = stringResource(R.string.apps_details_storage_locations_label),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )

            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                availablePaths.forEach { appPath ->
                    FilledTonalButton(
                        onClick = {
                            onAction(AppsActionBarItem.BrowsePath(app, appPath.path))
                            onDismiss()
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
                onClick = {
                    onAction(AppsActionBarItem.Launch(app))
                    onDismiss()
                }
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
                onClick = {
                    onAction(AppsActionBarItem.OpenInfo(app))
                    onDismiss()
                }
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
                    onClick = {
                        onAction(AppsActionBarItem.Disable(listOf(app)))
                        onDismiss()
                    }
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
                    onClick = {
                        onAction(AppsActionBarItem.Enable(listOf(app)))
                        onDismiss()
                    }
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
                onClick = {
                    onAction(AppsActionBarItem.Uninstall(listOf(app)))
                    onDismiss()
                }
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
                onClick = {
                    onAction(AppsActionBarItem.ExportApk(listOf(app)))
                    onDismiss()
                }
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
                onClick = {
                    onAction(AppsActionBarItem.Share(listOf(app)))
                    onDismiss()
                }
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
