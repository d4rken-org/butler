package eu.darken.butler.apps.ui.apps.items

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.Android
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import eu.darken.butler.apps.core.engine.AppItem
import eu.darken.butler.apps.ui.apps.preview.AppsMockDataProvider
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.theming.onScrim
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper

@Composable
fun AppGridItem(
    item: AppItem,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
    showSelection: Boolean = false,
) {
    val context = LocalContext.current
    val shape = RoundedCornerShape(8.dp)

    Card(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .border(
                width = if (isSelected) 2.dp else 0.5.dp,
                color = if (isSelected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                },
                shape = shape,
            ),
        shape = shape,
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = onLongClick,
                ),
        ) {
            // App icon centered
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (item.icon != null) {
                    AsyncImage(
                        model = item.icon.get(context),
                        contentDescription = null,
                        modifier = Modifier.size(56.dp),
                    )
                } else {
                    Icon(
                        imageVector = Icons.TwoTone.Android,
                        contentDescription = null,
                        modifier = Modifier.size(56.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // Tag chips in top-right
            if (item.tags.isNotEmpty()) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp),
                ) {
                    AppTagRow(
                        tags = item.tags,
                        compact = true,
                    )
                }
            }

            // Checkbox in top-left when in selection mode
            if (showSelection) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(4.dp),
                ) {
                    Checkbox(
                        checked = isSelected,
                        onCheckedChange = null,
                        modifier = Modifier.size(24.dp),
                    )
                }
            }

            // App name at the bottom
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.6f))
                    .padding(horizontal = 6.dp, vertical = 4.dp),
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.Start,
                    verticalArrangement = Arrangement.spacedBy(1.dp),
                ) {
                    Text(
                        text = item.label.get(context),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onScrim,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = item.packageName,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onScrim.copy(alpha = 0.7f),
                        maxLines = 1,
                        overflow = TextOverflow.MiddleEllipsis,
                    )
                }
            }
        }
    }
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun AppGridItemPreview() {
    AppGridItem(
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
private fun AppGridItemSelectedPreview() {
    AppGridItem(
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
private fun AppGridItemDisabledPreview() {
    AppGridItem(
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
private fun AppGridItemWithTagsPreview() {
    AppGridItem(
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
private fun AppGridItemSplitApkPreview() {
    AppGridItem(
        item = AppsMockDataProvider.Presets.splitApkItem,
        isSelected = false,
        onClick = {},
        onLongClick = {},
        showSelection = false,
    )
}

// Smallest real tile (GridSize.SMALL uses a 90dp minimum) with overlong label and package name.
@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun AppGridItemSmallTileLongNamesPreview() {
    Box(modifier = Modifier.width(90.dp)) {
        AppGridItem(
            item = AppsMockDataProvider.createMockAppItem(
                packageName = "com.superlongvendor.some.deeply.nested.application.identifier",
                label = "Very Long Application Name",
            ),
            isSelected = false,
            onClick = {},
            onLongClick = {},
            showSelection = true,
        )
    }
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun AppGridItemLargeTileLongNamesPreview() {
    Box(modifier = Modifier.width(160.dp)) {
        AppGridItem(
            item = AppsMockDataProvider.createMockAppItem(
                packageName = "com.superlongvendor.some.deeply.nested.application.identifier",
                label = "Very Long Application Name",
                isEnabled = false,
            ),
            isSelected = false,
            onClick = {},
            onLongClick = {},
            showSelection = false,
        )
    }
}
