package eu.darken.butler.apps.ui.apps.items

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.Android
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import eu.darken.butler.apps.R
import eu.darken.butler.apps.core.engine.AppItem
import eu.darken.butler.apps.ui.apps.preview.AppsMockDataProvider
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.DateTimeStyle
import eu.darken.butler.common.formatDateTime
import eu.darken.butler.workspace.contracts.apps.AppsViewStyle

const val APP_SIZE_TAG_ROW_TAG = "app-size-tag-row"

@Composable
fun AppListItem(
    item: AppItem,
    density: AppsViewStyle.Density,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
    showSelection: Boolean = false,
) {
    val context = LocalContext.current

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (isSelected) {
                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                } else {
                    Color.Transparent
                },
            )
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
            )
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Box(
            modifier = Modifier.size(density.rowIconSize),
            contentAlignment = Alignment.Center
        ) {
            if (showSelection) {
                Checkbox(
                    checked = isSelected,
                    onCheckedChange = null,
                )
            } else {
                if (item.icon != null) {
                    val fallbackPainter = rememberAppIconFallbackPainter()
                    AsyncImage(
                        model = item.pkg,
                        contentDescription = null,
                        modifier = Modifier.size(density.rowIconSize),
                        placeholder = fallbackPainter,
                        error = fallbackPainter,
                    )
                } else {
                    Icon(
                        imageVector = Icons.TwoTone.Android,
                        contentDescription = null,
                        modifier = Modifier.size(density.rowIconSize),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = item.label.get(context),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = item.packageName,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.MiddleEllipsis,
                )
                if (!item.versionName.isNullOrBlank() && density.showsSecondaryMetadata) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "v${item.versionName}",
                        modifier = Modifier.widthIn(max = 120.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            // Tags carry actionable state such as "Disabled", so compact keeps them and only
            // drops the size chip.
            val showsSizeChip = item.appSize != null && density.showsSecondaryMetadata
            if (item.tags.isNotEmpty() || showsSizeChip) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(APP_SIZE_TAG_ROW_TAG),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    // AppTagRow renders nothing without tags, so the spacer is what keeps a lone
                    // size chip pushed to the end of the row.
                    if (item.tags.isNotEmpty()) {
                        AppTagRow(
                            modifier = Modifier.weight(1f),
                            tags = item.tags,
                        )
                    } else {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                    if (showsSizeChip) {
                        AppSizeChip(bytes = item.appSize!!)
                    }
                }
            }
            if (density == AppsViewStyle.Density.DETAILED) {
                val installedAt = item.installedAt
                val updatedAt = item.updatedAt
                if (installedAt != null && updatedAt != null) {
                    Text(
                        text = stringResource(
                            R.string.apps_view_detail_dates_label,
                            formatDateTime(installedAt, DateTimeStyle.DATE_NUMERIC),
                            formatDateTime(updatedAt, DateTimeStyle.DATE_NUMERIC),
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun AppListItemPreview() {
    AppListItem(
        item = AppsMockDataProvider.Presets.chromeItem,
        density = AppsViewStyle.Density.COMFORTABLE,
        isSelected = false,
        onClick = {},
        onLongClick = {},
        showSelection = false,
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun AppListItemSelectedPreview() {
    AppListItem(
        item = AppsMockDataProvider.Presets.settingsItem,
        density = AppsViewStyle.Density.COMFORTABLE,
        isSelected = true,
        onClick = {},
        onLongClick = {},
        showSelection = true,
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun AppListItemDisabledPreview() {
    AppListItem(
        item = AppsMockDataProvider.Presets.disabledAppItem,
        density = AppsViewStyle.Density.COMFORTABLE,
        isSelected = false,
        onClick = {},
        onLongClick = {},
        showSelection = false,
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun AppListItemWithTagsPreview() {
    AppListItem(
        item = AppsMockDataProvider.Presets.multiTagAppItem,
        density = AppsViewStyle.Density.COMFORTABLE,
        isSelected = false,
        onClick = {},
        onLongClick = {},
        showSelection = false,
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun AppListItemWithSizeAndTagsPreview() {
    AppListItem(
        density = AppsViewStyle.Density.COMFORTABLE,
        item = AppsMockDataProvider.Presets.multiTagAppItem.copy(
            appSize = AppsMockDataProvider.MockSizes.gb(1),
        ),
        isSelected = false,
        onClick = {},
        onLongClick = {},
        showSelection = false,
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun AppListItemWithSizeNoTagsPreview() {
    AppListItem(
        density = AppsViewStyle.Density.COMFORTABLE,
        item = AppsMockDataProvider.createMockAppItem(
            packageName = "com.example.notes",
            label = "Notes",
            appSize = AppsMockDataProvider.MockSizes.mb(84),
        ),
        isSelected = false,
        onClick = {},
        onLongClick = {},
        showSelection = false,
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun AppListItemWithoutSizePreview() {
    AppListItem(
        density = AppsViewStyle.Density.COMFORTABLE,
        item = AppsMockDataProvider.createMockAppItem(
            packageName = "com.example.unmeasured",
            label = "Unmeasured App",
            appSize = null,
        ),
        isSelected = false,
        onClick = {},
        onLongClick = {},
        showSelection = false,
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun AppListItemSplitApkPreview() {
    AppListItem(
        item = AppsMockDataProvider.Presets.updatedSystemItem,
        density = AppsViewStyle.Density.COMFORTABLE,
        isSelected = false,
        onClick = {},
        onLongClick = {},
        showSelection = false,
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun AppListItemLongVersionNarrowPreview() {
    Box(modifier = Modifier.width(240.dp)) {
        AppListItem(
            density = AppsViewStyle.Density.COMFORTABLE,
            item = AppsMockDataProvider.createMockAppItem(
                packageName = "com.superlongvendor.some.deeply.nested.application.identifier",
                label = "Very Long Application Name",
                versionName = "12.34.5678-beta.20250724+build",
                appSize = AppsMockDataProvider.MockSizes.mb(84),
            ),
            isSelected = false,
            onClick = {},
            onLongClick = {},
            showSelection = false,
        )
    }
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun AppListItemCompactPreview() {
    AppListItem(
        item = AppsMockDataProvider.Presets.disabledAppItem.copy(
            appSize = AppsMockDataProvider.MockSizes.mb(84),
        ),
        density = AppsViewStyle.Density.COMPACT,
        isSelected = false,
        onClick = {},
        onLongClick = {},
        showSelection = false,
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun AppListItemDetailedPreview() {
    AppListItem(
        item = AppsMockDataProvider.Presets.chromeItem.copy(
            appSize = AppsMockDataProvider.MockSizes.mb(84),
        ),
        density = AppsViewStyle.Density.DETAILED,
        isSelected = false,
        onClick = {},
        onLongClick = {},
        showSelection = false,
    )
}
