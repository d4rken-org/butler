package eu.darken.butler.explorer.ui.explorer.elements

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.twotone.InsertDriveFile
import androidx.compose.material.icons.twotone.Bookmark
import androidx.compose.material.icons.twotone.Close
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
    onClick: (FavoriteItem) -> Unit,
    onRemove: (FavoriteItem) -> Unit,
) {
    if (favorites.isEmpty()) return

    item(key = "favorites:header") { FavoritesSectionHeader() }

    items(favorites, key = { favoriteKey(it) }) { favorite ->
        FavoriteRow(
            favorite = favorite,
            isHighlighted = favorite.path.toPathItemId() in highlightedItemIds,
            onClick = { onClick(favorite) },
            onRemove = { onRemove(favorite) },
        )
    }
}

/** Grid-scope variant: header spans the full row, items each take a cell. */
fun LazyGridScope.favoritesSection(
    favorites: List<FavoriteItem>,
    highlightedItemIds: Set<String>,
    onClick: (FavoriteItem) -> Unit,
    onRemove: (FavoriteItem) -> Unit,
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
            onClick = { onClick(favorite) },
            onRemove = { onRemove(favorite) },
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

@Composable
fun FavoriteRow(
    modifier: Modifier = Modifier,
    favorite: FavoriteItem,
    isHighlighted: Boolean = false,
    onClick: () -> Unit,
    onRemove: () -> Unit,
) {
    val context = LocalContext.current
    val isResolving = favorite.state is FavoriteItem.State.Resolving
    val isUnavailable = favorite.state is FavoriteItem.State.Unavailable

    val displayName = when (val s = favorite.state) {
        is FavoriteItem.State.Available -> s.item.displayName.get(context)
        else -> favorite.path.userReadableName.get(context)
    }
    val subtitle = when {
        isUnavailable -> stringResource(R.string.explorer_favorites_unavailable_subtitle)
        isResolving -> ""
        else -> favorite.path.userReadablePath.get(context)
    }

    val removeLabel = stringResource(R.string.explorer_favorites_remove_action)

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

    Row(
        modifier = modifier
            .fillMaxWidth()
            .dropZone(key = favoriteKey(favorite), destination = favorite.dropDestination())
            .clip(RoundedCornerShape(8.dp))
            .background(highlightColor, RoundedCornerShape(8.dp))
            .clickable(enabled = !isResolving) { onClick() }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Leading icon — mirrors ShortcutRow exactly: 40dp box, 20dp icon, primaryContainer.
        // Unavailable uses surfaceVariant to signal "inaccessible" without dimming the row.
        val iconBackground = if (isUnavailable) {
            MaterialTheme.colorScheme.surfaceVariant
        } else {
            MaterialTheme.colorScheme.primaryContainer
        }
        val iconTint = if (isUnavailable) {
            MaterialTheme.colorScheme.onSurfaceVariant
        } else {
            MaterialTheme.colorScheme.onPrimaryContainer
        }
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(iconBackground),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = pickIcon(favorite),
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
            if (subtitle.isNotEmpty()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        // Remove button — 40dp clickable area, 18dp icon. Always shown so a stuck-resolving
        // favorite can still be dismissed. Avoids IconButton's 48dp interactive minimum
        // which previously inflated the row past ShortcutRow's height.
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .clickable(onClickLabel = removeLabel) { onRemove() },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.TwoTone.Close,
                contentDescription = removeLabel,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
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
        onRemove = {},
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
        onRemove = {},
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
        onRemove = {},
    )
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
            onClick = {},
            onRemove = {},
        )
    }
}
