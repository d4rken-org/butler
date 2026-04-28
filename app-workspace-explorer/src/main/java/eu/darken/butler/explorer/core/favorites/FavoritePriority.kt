package eu.darken.butler.explorer.core.favorites

import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.extensions.matches
import eu.darken.butler.explorer.core.engine.ExplorerItem
import eu.darken.butler.explorer.core.engine.ExplorerLocation
import eu.darken.butler.explorer.core.picker.PickerConfig

/**
 * In a directory listing (not Device, Trash, or any picker workspace), pull favorited
 * directories to the top of the list while preserving the user's sort order within the
 * favorites group. Files that are favorited keep their natural sort position — pinning
 * files would break the existing "directories before files" sort contract enforced by
 * [eu.darken.butler.explorer.core.sorting.ExplorerItemSorter].
 *
 * Returns the input list unchanged when:
 * - the location is not a [ExplorerLocation.Directory] (Home, Device, Trash);
 * - the workspace is in picker mode;
 * - there are no favorites;
 * - none of the items are pin-eligible directories that match a favorite path.
 */
internal fun applyFavoritePriority(
    items: List<ExplorerItem>,
    location: ExplorerLocation?,
    pickerConfig: PickerConfig?,
    favoritePaths: List<APath<*>>,
): List<ExplorerItem> {
    if (location !is ExplorerLocation.Directory) return items
    if (pickerConfig != null) return items
    if (favoritePaths.isEmpty()) return items
    val (pinned, rest) = items.partition { item ->
        item is ExplorerItem.Directory && favoritePaths.any { it.matches(item.path) }
    }
    if (pinned.isEmpty()) return items
    return pinned + rest
}
