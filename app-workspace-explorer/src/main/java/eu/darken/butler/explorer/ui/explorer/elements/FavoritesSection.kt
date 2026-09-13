package eu.darken.butler.explorer.ui.explorer.elements

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.twotone.InsertDriveFile
import androidx.compose.material.icons.twotone.Bookmark
import androidx.compose.material.icons.twotone.Check
import androidx.compose.material.icons.twotone.Folder
import androidx.compose.material.icons.twotone.FolderOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.ArchivePath
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.extensions.matches
import eu.darken.butler.common.files.local.LocalPathLookup
import eu.darken.butler.common.files.metadata.FileType
import eu.darken.butler.explorer.R
import eu.darken.butler.explorer.core.engine.ExplorerItem
import eu.darken.butler.explorer.core.engine.ExplorerItem.Path.Companion.toPathItemId
import eu.darken.butler.explorer.core.favorites.FavoriteItem
import eu.darken.butler.explorer.ui.explorer.ExplorerWorkspaceViewModel
import eu.darken.butler.workspace.ui.dnd.dropZone

/** List-scope variant: header item + a row per favorite. */
fun LazyListScope.favoritesSection(
    favorites: List<FavoriteItem>,
    highlightedItemIds: Set<String>,
    selectedPaths: Set<APath<*>>,
    onClick: (FavoriteItem) -> Unit,
    onLongClick: (FavoriteItem) -> Unit,
) {
    if (favorites.isEmpty()) return

    item(key = "favorites:header") { FavoritesSectionHeader() }

    items(favorites, key = { favoriteKey(it) }) { favorite ->
        FavoriteRow(
            favorite = favorite,
            isHighlighted = favorite.path.toPathItemId() in highlightedItemIds,
            isSelected = selectedPaths.any { it.matches(favorite.path) },
            isSelectionMode = selectedPaths.isNotEmpty(),
            onClick = { onClick(favorite) },
            onLongClick = { onLongClick(favorite) },
        )
    }
}

/** Grid-scope variant: header spans the full row, items each take a cell. */
fun LazyGridScope.favoritesSection(
    favorites: List<FavoriteItem>,
    highlightedItemIds: Set<String>,
    selectedPaths: Set<APath<*>>,
    onClick: (FavoriteItem) -> Unit,
    onLongClick: (FavoriteItem) -> Unit,
) {
    if (favorites.isEmpty()) return

    item(
        key = "favorites:header",
        span = { GridItemSpan(maxLineSpan) },
    ) {
        FavoritesSectionHeader()
    }

    gridItems(
        items = favorites,
        key = { favoriteKey(it) },
        span = { GridItemSpan(maxLineSpan) },
    ) { favorite ->
        // Even in grid mode, favorites render as full-width rows so the path subtitle
        // remains legible (a folder named "Music" can come from many roots).
        FavoriteRow(
            favorite = favorite,
            isHighlighted = favorite.path.toPathItemId() in highlightedItemIds,
            isSelected = selectedPaths.any { it.matches(favorite.path) },
            isSelectionMode = selectedPaths.isNotEmpty(),
            onClick = { onClick(favorite) },
            onLongClick = { onLongClick(favorite) },
        )
    }
}

/**
 * Lazy index of the favorite for [path], for scrolling it into view.
 *
 * Mirrors the layout both [ExplorerListContent] and [ExplorerGridContent] build — leading content
 * block, divider, section header, then one entry per favorite — and must be updated with them.
 *
 * @return `null` while the favorites section isn't showing, the content is still loading, or the
 *         path isn't among the favorites yet.
 */
internal fun ExplorerWorkspaceViewModel.State.favoriteContentIndex(path: APath<*>): Int? {
    if (!showHomeFavoritesSection) return null
    val favoriteIndex = favorites.indexOfFirst { it.path.matches(path) }
    if (favoriteIndex < 0) return null
    val leadingItems = when {
        // No items: an error renders nothing above the section, loading renders skeletons whose
        // count is about to change — so wait for the content instead of pointing at a skeleton.
        items == null -> if (error != null) 0 else return null
        items.isEmpty() -> 1 // the empty-state item
        else -> items.size
    }
    return leadingItems + 2 + favoriteIndex // + divider + section header
}

@Composable
private fun FavoritesSectionHeader(
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 8.dp, start = 16.dp, end = 16.dp, bottom = 0.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = Icons.TwoTone.Bookmark,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.tertiary,
            modifier = Modifier.size(16.dp),
        )
        Text(
            text = stringResource(R.string.explorer_favorites_section_title),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Stable lazy-list key for a favorite. Includes the APath subtype because the polymorphic
 * registry permits LocalPath and SAFPath with identical rendered strings to coexist.
 */
private fun favoriteKey(favorite: FavoriteItem): String =
    "favorite:${favorite.path::class.simpleName}:${favorite.path.path}"

/**
 * @param isSelectionMode while favorites are being selected a tap selects instead of navigating,
 *        which is what keeps a favorite that never resolves reachable.
 */
@Composable
fun FavoriteRow(
    modifier: Modifier = Modifier,
    favorite: FavoriteItem,
    isHighlighted: Boolean = false,
    isSelected: Boolean = false,
    isSelectionMode: Boolean = false,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val context = LocalContext.current
    val isResolving = favorite.state is FavoriteItem.State.Resolving
    val isUnavailable = favorite.state is FavoriteItem.State.Unavailable

    val displayName = favorite.displayName().get(context)
    // Always the real path: with a custom name as the title it is the only thing that tells two
    // favorites apart, so neither resolving nor unavailable may take its place.
    val subtitle = favorite.path.userReadablePath.get(context)

    // Same reveal tint the file rows use, see FileRowBase.
    val highlightColor by animateColorAsState(
        targetValue = if (isHighlighted) {
            MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.5f)
        } else {
            Color.Transparent
        },
        animationSpec = tween(durationMillis = 300),
        label = "highlightColor",
    )

    // Selection takes precedence over the reveal tint, as in FileRowBase.
    val backgroundColor = when {
        isSelected -> MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.3f)
        else -> highlightColor
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .dropZone(key = favoriteKey(favorite), destination = favorite.dropDestination())
            .clip(RoundedCornerShape(8.dp))
            .background(backgroundColor, RoundedCornerShape(8.dp))
            .combinedClickable(
                // Long-press and selection-mode taps stay live whatever the resolve state: a
                // favorite whose lookup never completes must still be selectable, and with that
                // removable. Only navigation waits for a resolved item.
                onClick = { if (isSelectionMode || !isResolving) onClick() },
                onLongClick = onLongClick,
            )
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Leading icon — mirrors ShortcutRow exactly: 40dp box, 20dp icon, primaryContainer.
        // Unavailable uses surfaceVariant to signal "inaccessible" without dimming the row.
        val iconBackground = when {
            isSelected -> MaterialTheme.colorScheme.secondaryContainer
            isUnavailable -> MaterialTheme.colorScheme.surfaceVariant
            else -> MaterialTheme.colorScheme.primaryContainer
        }
        val iconTint = when {
            isSelected -> MaterialTheme.colorScheme.onSecondaryContainer
            isUnavailable -> MaterialTheme.colorScheme.onSurfaceVariant
            else -> MaterialTheme.colorScheme.onPrimaryContainer
        }
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(iconBackground),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (isSelected) Icons.TwoTone.Check else pickIcon(favorite),
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = iconTint,
            )
        }

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = displayName,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (isUnavailable) {
                Text(
                    text = stringResource(R.string.explorer_favorites_unavailable_subtitle),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** Only a resolved directory can take a drop; a file or an unavailable favorite has no destination. */
private fun FavoriteItem.dropDestination(): APath<*>? {
    val item = (state as? FavoriteItem.State.Available)?.item as? ExplorerItem.Directory ?: return null
    return item.path.takeUnless { it is ArchivePath }
}

private fun pickIcon(favorite: FavoriteItem): ImageVector = when {
    favorite.isUnavailable -> Icons.TwoTone.FolderOff
    favorite.isFile -> Icons.AutoMirrored.TwoTone.InsertDriveFile
    else -> Icons.TwoTone.Folder
}

// ----- Previews -----

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun FavoriteRowAvailableDirectoryPreview() {
    FavoriteRow(
        favorite = FavoriteItem(
            path = LocalPath.build("/storage/emulated/0/Download"),
            state = FavoriteItem.State.Available(
                ExplorerItem.RegularDirectory(
                    lookup = LocalPathLookup(
                        lookedUp = LocalPath.build("/storage/emulated/0/Download"),
                        fileType = FileType.DIRECTORY,
                        size = null,
                        modifiedAt = null,
                    ),
                ),
            ),
        ),
        onClick = {},
        onLongClick = {},
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun FavoriteRowResolvingPreview() {
    FavoriteRow(
        favorite = FavoriteItem(
            path = LocalPath.build("/storage/emulated/0/DCIM"),
            state = FavoriteItem.State.Resolving,
        ),
        onClick = {},
        onLongClick = {},
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun FavoriteRowUnavailablePreview() {
    FavoriteRow(
        favorite = FavoriteItem(
            path = LocalPath.build("/storage/emulated/0/RemovedFolder"),
            state = FavoriteItem.State.Unavailable(IllegalStateException("not found")),
        ),
        onClick = {},
        onLongClick = {},
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun FavoriteRowSelectedPreview() {
    FavoriteRow(
        favorite = FavoriteItem(
            path = LocalPath.build("/storage/emulated/0/Download"),
            state = FavoriteItem.State.Available(
                ExplorerItem.RegularDirectory(
                    lookup = LocalPathLookup(
                        lookedUp = LocalPath.build("/storage/emulated/0/Download"),
                        fileType = FileType.DIRECTORY,
                        size = null,
                        modifiedAt = null,
                    ),
                ),
            ),
        ),
        isSelected = true,
        isSelectionMode = true,
        onClick = {},
        onLongClick = {},
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun FavoriteRowLabeledPreview() {
    FavoriteRow(
        favorite = FavoriteItem(
            path = LocalPath.build("/storage/emulated/0/DCIM/Camera"),
            state = FavoriteItem.State.Available(
                ExplorerItem.RegularDirectory(
                    lookup = LocalPathLookup(
                        lookedUp = LocalPath.build("/storage/emulated/0/DCIM/Camera"),
                        fileType = FileType.DIRECTORY,
                        size = null,
                        modifiedAt = null,
                    ),
                ),
            ),
            label = "Camera roll",
        ),
        onClick = {},
        onLongClick = {},
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun FavoriteRowLabeledResolvingPreview() {
    FavoriteRow(
        favorite = FavoriteItem(
            path = LocalPath.build("/storage/emulated/0/DCIM"),
            state = FavoriteItem.State.Resolving,
            label = "Camera roll",
        ),
        onClick = {},
        onLongClick = {},
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun FavoriteRowLabeledUnavailablePreview() {
    FavoriteRow(
        favorite = FavoriteItem(
            path = LocalPath.build("/storage/emulated/0/RemovedFolder"),
            state = FavoriteItem.State.Unavailable(IllegalStateException("not found")),
            label = "Camera roll",
        ),
        onClick = {},
        onLongClick = {},
    )
}

/** Two favorites under one name: only the path subtitle tells them apart. */
@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun FavoritesSectionSharedLabelPreview() {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        favoritesSection(
            favorites = listOf(
                FavoriteItem(
                    path = LocalPath.build("/storage/emulated/0/DCIM/Camera"),
                    state = FavoriteItem.State.Resolving,
                    label = "Photos",
                ),
                FavoriteItem(
                    path = LocalPath.build("/storage/1A2B-3C4D/DCIM/Camera"),
                    state = FavoriteItem.State.Resolving,
                    label = "Photos",
                ),
            ),
            highlightedItemIds = emptySet(),
            selectedPaths = emptySet(),
            onClick = {},
            onLongClick = {},
        )
    }
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun FavoritesSectionMixedPreview() {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        favoritesSection(
            favorites = listOf(
                FavoriteItem(
                    path = LocalPath.build("/storage/emulated/0/Download"),
                    state = FavoriteItem.State.Available(
                        ExplorerItem.RegularDirectory(
                            lookup = LocalPathLookup(
                                lookedUp = LocalPath.build("/storage/emulated/0/Download"),
                                fileType = FileType.DIRECTORY,
                                size = null,
                                modifiedAt = null,
                            ),
                        ),
                    ),
                ),
                FavoriteItem(
                    path = LocalPath.build("/storage/emulated/0/DCIM"),
                    state = FavoriteItem.State.Resolving,
                ),
                FavoriteItem(
                    path = LocalPath.build("/storage/emulated/0/RemovedFolder"),
                    state = FavoriteItem.State.Unavailable(IllegalStateException("not found")),
                ),
            ),
            highlightedItemIds = setOf(LocalPath.build("/storage/emulated/0/Download").toPathItemId()),
            selectedPaths = emptySet(),
            onClick = {},
            onLongClick = {},
        )
    }
}
