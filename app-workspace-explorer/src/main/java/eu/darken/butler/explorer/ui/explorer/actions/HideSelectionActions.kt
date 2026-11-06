package eu.darken.butler.explorer.ui.explorer.actions

import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.ArchivePath
import eu.darken.butler.explorer.core.engine.ExplorerItem

/**
 * Hide for the selected paths that are not on the hide list, Unhide for those that are, so a mixed
 * selection gets both, each acting on its own part. Archive entries and non-path rows get neither.
 */
internal fun hideSelectionActions(
    selectedItems: Collection<ExplorerItem>,
    isHidden: (APath<*>) -> Boolean,
): List<ExplorerActionBarItem> {
    val paths = selectedItems
        .filterIsInstance<ExplorerItem.Lookup>()
        .map { it.path }
        .filterNot { it is ArchivePath }
    if (paths.isEmpty()) return emptyList()

    val (hidden, visible) = paths.partition(isHidden)
    return buildList {
        if (visible.isNotEmpty()) add(ExplorerActionBarItem.Common.HidePaths(visible))
        if (hidden.isNotEmpty()) add(ExplorerActionBarItem.Common.UnhidePaths(hidden))
    }
}
