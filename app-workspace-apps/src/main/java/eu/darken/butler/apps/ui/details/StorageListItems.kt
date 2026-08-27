package eu.darken.butler.apps.ui.details

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.Folder
import androidx.compose.material.icons.twotone.Storage
import androidx.compose.material.icons.twotone.Warning
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import androidx.compose.ui.unit.dp
import eu.darken.butler.apps.R
import eu.darken.butler.apps.core.AppPath
import eu.darken.butler.apps.core.details.AppInfo
import eu.darken.butler.apps.ui.apps.preview.AppsMockDataProvider
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.R as CommonR
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.formatFileSize

@Composable
fun StorageListItems(
    modifier: Modifier = Modifier,
    availablePaths: List<AppPath>,
    onBrowsePath: (APath<*>) -> Unit,
    onOpenSetup: () -> Unit,
    app: AppInfo? = null,
    isLoadingSize: Boolean = false,
    sizesAvailable: Boolean = true,
) {
    val hasSizeData = app?.appSize != null || app?.cacheSize != null || app?.dataSize != null
    // The loading and permission states have to render even without any size data yet.
    if (availablePaths.isEmpty() && !hasSizeData && !isLoadingSize && sizesAvailable) return

    val context = LocalContext.current

    Column(modifier = modifier.fillMaxWidth()) {
        if (hasSizeData || isLoadingSize || !sizesAvailable) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .background(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                        shape = RoundedCornerShape(8.dp)
                    )
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.apps_storage_breakdown_label),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    // Without usage access the breakdown below is replaced by the setup block, so a
                    // grand total from a stale cache would be left hanging above nothing.
                    val totalSize = if (sizesAvailable) app?.totalSize ?: 0L else 0L
                    if (totalSize > 0) {
                        Text(
                            text = formatFileSize(totalSize),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                when {
                    // Takes precedence over cached sizes: without usage access Android stops
                    // reporting them, so whatever is still cached is stale and the user needs the
                    // setup path rather than numbers that will never update.
                    !sizesAvailable -> {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = Icons.TwoTone.Warning,
                                contentDescription = null,
                                modifier = Modifier.size(24.dp),
                                tint = MaterialTheme.colorScheme.error,
                            )
                            Text(
                                text = stringResource(CommonR.string.setup_required_card_title),
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }

                        Text(
                            text = stringResource(R.string.apps_size_permission_body),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp),
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                        ) {
                            TextButton(onClick = onOpenSetup) {
                                Text(stringResource(CommonR.string.setup_required_card_setup_action))
                            }
                        }
                    }

                    !hasSizeData && isLoadingSize -> {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }

                    else -> {
                        // Storage breakdown bars
                        val appSize = app?.appSize
                        if (appSize != null) {
                            StorageItem(
                                label = stringResource(R.string.apps_storage_app_label),
                                size = appSize,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }

                        val dataSize = app?.dataSize
                        if (dataSize != null && dataSize > 0) {
                            StorageItem(
                                label = stringResource(R.string.apps_data_size_label),
                                size = dataSize,
                                color = MaterialTheme.colorScheme.secondary
                            )
                        }

                        val cacheSize = app?.cacheSize
                        if (cacheSize != null && cacheSize > 0) {
                            StorageItem(
                                label = stringResource(R.string.apps_cache_size_label),
                                size = cacheSize,
                                color = MaterialTheme.colorScheme.tertiary
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
        }

        // Storage Paths
        availablePaths.forEachIndexed { index, appPath ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onBrowsePath(appPath.path) }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = if (appPath.path.path.contains("external")) {
                        Icons.TwoTone.Storage
                    } else {
                        Icons.TwoTone.Folder
                    },
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.primary
                )

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        text = appPath.label.get(context),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        text = appPath.path.path,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    // The row stays clickable: browsing there is what leads to the setup options.
                    appPath.requirement?.let {
                        Text(
                            text = it.get(context),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                    }
                }
            }

            if (index < availablePaths.size - 1) {
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            }
        }
    }
}

@Composable
private fun StorageItem(
    label: String,
    size: Long,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(color)
            )
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text(
            text = formatFileSize(size),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium
        )
    }
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun StorageListItemsPreview() {
    StorageListItems(
        availablePaths = emptyList(),
        onBrowsePath = {},
        onOpenSetup = {},
        app = null
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun StorageListItemsWithPathsPreview() {
    StorageListItems(
        availablePaths = listOf(
            AppPath(
                path = LocalPath.build("/data/data/com.android.chrome"),
                label = R.string.apps_path_internal_data_label.toCaString(),
                requirement = R.string.apps_path_requires_root_label.toCaString(),
            ),
            AppPath(
                path = LocalPath.build("/storage/emulated/0/Android/data/com.android.chrome"),
                label = R.string.apps_path_external_data_label.toCaString(),
            ),
        ),
        onBrowsePath = {},
        onOpenSetup = {},
        app = AppsMockDataProvider.Presets.chrome,
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun StorageListItemsWithSizesPreview() {
    StorageListItems(
        availablePaths = emptyList(),
        onBrowsePath = {},
        onOpenSetup = {},
        app = AppsMockDataProvider.Presets.chrome,
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun StorageListItemsLoadingPreview() {
    StorageListItems(
        availablePaths = emptyList(),
        onBrowsePath = {},
        onOpenSetup = {},
        app = AppsMockDataProvider.createMockAppInfo(appSize = null, dataSize = null, cacheSize = null),
        isLoadingSize = true,
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun StorageListItemsNoUsageAccessPreview() {
    StorageListItems(
        availablePaths = emptyList(),
        onBrowsePath = {},
        onOpenSetup = {},
        app = AppsMockDataProvider.createMockAppInfo(appSize = null, dataSize = null, cacheSize = null),
        sizesAvailable = false,
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun StorageListItemsNoUsageAccessWithCachedSizesPreview() {
    StorageListItems(
        availablePaths = emptyList(),
        onBrowsePath = {},
        onOpenSetup = {},
        app = AppsMockDataProvider.Presets.chrome,
        sizesAvailable = false,
    )
}
