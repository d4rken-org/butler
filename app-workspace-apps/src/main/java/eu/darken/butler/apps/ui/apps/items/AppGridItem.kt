package eu.darken.butler.apps.ui.apps.items

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import eu.darken.butler.workspace.contracts.apps.AppsViewStyle

@Composable
fun AppGridItem(
    item: AppItem,
    density: AppsViewStyle.Density,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
    showSelection: Boolean = false,
) {
    val context = LocalContext.current
    val shape = RoundedCornerShape(8.dp)
    val showsVersionInCorner =
        density == AppsViewStyle.Density.DETAILED && !item.versionName.isNullOrBlank()

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
                    val fallbackPainter = rememberAppIconFallbackPainter()
                    AsyncImage(
                        model = item.pkg,
                        contentDescription = null,
                        modifier = Modifier.size(density.gridIconSize),
                        placeholder = fallbackPainter,
                        error = fallbackPainter,
                    )
                } else {
                    Icon(
                        imageVector = Icons.TwoTone.Android,
                        contentDescription = null,
                        modifier = Modifier.size(density.gridIconSize),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // Top row: the selection checkbox or, at detailed density, the version on the left;
            // tag chips on the right. One row rather than two corner-aligned boxes, so a long
            // version ellipsizes against the chips instead of running underneath them.
            //
            // Tags carry actionable state such as "Disabled", so unlike the package name and the
            // size they survive every density.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter)
                    .padding(4.dp),
                verticalAlignment = Alignment.Top,
                // Leftover width belongs between the two slots, so the chips stay on the right
                // edge whatever the leading slot measures. A weighted spacer would keep half of
                // it for itself and leave the chips floating mid-row.
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                when {
                    showSelection -> Checkbox(
                        checked = isSelected,
                        onCheckedChange = null,
                        modifier = Modifier.size(24.dp),
                    )
                    showsVersionInCorner -> Text(
                        text = "v${item.versionName}",
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .padding(horizontal = 2.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    // SpaceBetween needs two children to have anything to space apart.
                    else -> Spacer(modifier = Modifier)
                }
                AppTagRow(
                    tags = item.tags,
                    compact = true,
                )
            }

            // Size chip floats above the label scrim; stacking them keeps it clear of the
            // overlay without hardcoding the overlay's (dynamic, two-line) height.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter),
            ) {
                if (item.appSize != null && density.showsSecondaryMetadata) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(4.dp),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        AppSizeChip(
                            bytes = item.appSize,
                            compact = true,
                        )
                    }
                }

                // App name at the bottom
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
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
                        if (density.showsSecondaryMetadata) {
                            Text(
                                text = item.packageName,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onScrim.copy(alpha = 0.7f),
                                maxLines = 1,
                                overflow = TextOverflow.MiddleEllipsis,
                            )
                        }
                        // The checkbox has the corner while selecting, so the version falls back
                        // to the label overlay rather than disappearing.
                        if (showSelection && showsVersionInCorner) {
                            Text(
                                text = "v${item.versionName}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onScrim.copy(alpha = 0.7f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
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
private fun AppGridItemSelectedPreview() {
    AppGridItem(
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
private fun AppGridItemDisabledPreview() {
    AppGridItem(
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
private fun AppGridItemWithTagsPreview() {
    AppGridItem(
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
private fun AppGridItemSplitApkPreview() {
    AppGridItem(
        item = AppsMockDataProvider.Presets.splitApkItem,
        density = AppsViewStyle.Density.COMFORTABLE,
        isSelected = false,
        onClick = {},
        onLongClick = {},
        showSelection = false,
    )
}

// Smallest real tile (the compact density uses a 90dp minimum) with overlong label and package name.
@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun AppGridItemSmallTileLongNamesPreview() {
    Box(modifier = Modifier.width(90.dp)) {
        AppGridItem(
            density = AppsViewStyle.Density.COMFORTABLE,
            item = AppsMockDataProvider.createMockAppItem(
                packageName = "com.superlongvendor.some.deeply.nested.application.identifier",
                label = "Very Long Application Name",
                appSize = AppsMockDataProvider.MockSizes.gb(3),
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
            density = AppsViewStyle.Density.COMFORTABLE,
            item = AppsMockDataProvider.createMockAppItem(
                packageName = "com.superlongvendor.some.deeply.nested.application.identifier",
                label = "Very Long Application Name",
                isEnabled = false,
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
private fun AppGridItemWithoutSizePreview() {
    Box(modifier = Modifier.width(90.dp)) {
        AppGridItem(
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
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun AppGridItemCompactPreview() {
    AppGridItem(
        item = AppsMockDataProvider.Presets.multiTagAppItem,
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
private fun AppGridItemDetailedPreview() {
    AppGridItem(
        item = AppsMockDataProvider.Presets.multiTagAppItem,
        density = AppsViewStyle.Density.DETAILED,
        isSelected = false,
        onClick = {},
        onLongClick = {},
        showSelection = false,
    )
}
