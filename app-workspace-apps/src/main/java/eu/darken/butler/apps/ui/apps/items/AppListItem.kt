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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import eu.darken.butler.apps.core.engine.AppItem
import eu.darken.butler.apps.ui.apps.preview.AppsMockDataProvider
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.formatFileSize

@Composable
fun AppListItem(
    item: AppItem,
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
            .padding(horizontal = 8.dp)
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
            modifier = Modifier.size(40.dp),
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
                        modifier = Modifier.size(40.dp),
                        placeholder = fallbackPainter,
                        error = fallbackPainter,
                    )
                } else {
                    Icon(
                        imageVector = Icons.TwoTone.Android,
                        contentDescription = null,
                        modifier = Modifier.size(40.dp),
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
                if (!item.versionName.isNullOrBlank()) {
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
            if (item.appSize != null) {
                Text(
                    text = formatFileSize(item.appSize),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (item.tags.isNotEmpty()) {
                AppTagRow(tags = item.tags)
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
            item = AppsMockDataProvider.createMockAppItem(
                packageName = "com.superlongvendor.some.deeply.nested.application.identifier",
                label = "Very Long Application Name",
                versionName = "12.34.5678-beta.20250724+build",
                appSize = null,
            ),
            isSelected = false,
            onClick = {},
            onLongClick = {},
            showSelection = false,
        )
    }
}
