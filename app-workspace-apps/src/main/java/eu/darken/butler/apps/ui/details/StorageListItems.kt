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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.formatFileSize

@Composable
fun StorageListItems(
    modifier: Modifier = Modifier,
    availablePaths: List<AppPath>,
    onBrowsePath: (APath<*>) -> Unit,
    app: AppInfo? = null,
) {
    if (availablePaths.isEmpty() && app?.appSize == null && app?.cacheSize == null && app?.dataSize == null) return

    val context = LocalContext.current

    Column(modifier = modifier.fillMaxWidth()) {
        // Storage Overview (if size data available)
        if (app?.appSize != null || app?.cacheSize != null || app?.dataSize != null) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
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

                    val totalSize = (app.appSize ?: 0L) + (app.dataSize ?: 0L) + (app.cacheSize ?: 0L)
                    if (totalSize > 0) {
                        Text(
                            text = formatFileSize(totalSize),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                // Storage breakdown bars
                if (app.appSize != null) {
                    StorageItem(
                        label = stringResource(R.string.apps_storage_app_label),
                        size = app.appSize,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                if (app.dataSize != null && app.dataSize > 0) {
                    StorageItem(
                        label = stringResource(R.string.apps_data_size_label),
                        size = app.dataSize,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }

                if (app.cacheSize != null && app.cacheSize > 0) {
                    StorageItem(
                        label = stringResource(R.string.apps_cache_size_label),
                        size = app.cacheSize,
                        color = MaterialTheme.colorScheme.tertiary
                    )
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
                    .padding(vertical = 12.dp),
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
                }
            }

            if (index < availablePaths.size - 1) {
                HorizontalDivider()
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
        app = null
    )
}
