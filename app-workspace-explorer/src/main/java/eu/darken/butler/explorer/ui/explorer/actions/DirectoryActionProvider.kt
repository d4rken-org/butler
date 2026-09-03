package eu.darken.butler.explorer.ui.explorer.actions

import eu.darken.butler.common.files.ArchivePath
import eu.darken.butler.common.files.archive.ArchiveFormat
import eu.darken.butler.explorer.core.ExplorerViewStyle
import eu.darken.butler.explorer.core.engine.ExplorerItem
import eu.darken.butler.explorer.core.engine.ExplorerLocation
import eu.darken.butler.explorer.core.favorites.ExplorerFavoritesRepo
import eu.darken.butler.explorer.core.toggled
import eu.darken.butler.explorer.ui.explorer.util.ExplorerSelectionState
import eu.darken.butler.workspace.ui.actions.FileActionCapabilities
import javax.inject.Inject

class DirectoryActionProvider @Inject constructor(
    private val favoritesRepo: ExplorerFavoritesRepo,
) : ExplorerActionProvider {

    override fun getActions(
        location: ExplorerLocation,
        selectionState: ExplorerSelectionState,
        viewStyle: ExplorerViewStyle,
        trashEnabled: Boolean,
    ): List<ExplorerActionBarItem> {
        val actions = mutableListOf<ExplorerActionBarItem>()

        val directory = location as? ExplorerLocation.Directory
        // Archive contents are structurally read-only. Check the path type directly (holds even before
        // the async writability pass resolves info.isWritable) on both the current location AND every
        // selected item, so a stale/transitioning selection carrying an archive entry into a real
        // directory can't re-expose the mutating actions.
        val isArchiveLocation = directory?.path is ArchivePath
        val selectionHasArchiveEntry = selectionState.selectedItems.any {
            it is ExplorerItem.Lookup && it.path is ArchivePath
        }
        val isReadOnlySource = isArchiveLocation || selectionHasArchiveEntry
        val isWritable = !isReadOnlySource && (directory?.info?.isWritable ?: false)
        // Genuinely empty folder (raw, unfiltered items). null = still loading -> not treated as empty.
        val isEmpty = directory?.items?.isEmpty() == true

        if (selectionState.isSelectionMode) {
            if (!selectionState.isAllSelected) {
                actions.add(ExplorerActionBarItem.Directory.SelectAll)
            }

            actions.add(ExplorerActionBarItem.Directory.OpenInNewTabs())

            // Rename/Cut/Delete mutate the source, so they're hidden for read-only archive content.
            if (selectionState.selectionCount == 1 && !isReadOnlySource) {
                actions.add(ExplorerActionBarItem.Directory.Rename())
            }

            actions.add(ExplorerActionBarItem.Directory.Copy())

            if (!isReadOnlySource) {
                actions.add(
                    ExplorerActionBarItem.Directory.Cut(
                        isEnabled = isWritable,
                    )
                )

                actions.add(
                    ExplorerActionBarItem.Directory.Delete(
                        isEnabled = isWritable,
                        trashEnabled = trashEnabled,
                    )
                )
            }

            // Handing a file to another app needs a URI the system can resolve, which is what
            // FileActionCapabilities answers, so a selection holding anything else - or a folder -
            // is not offered for sharing.
            val selectionIsShareable = selectionState.selectedItems.all {
                it is ExplorerItem.File && FileActionCapabilities.canHandOffToOtherApps(it.path)
            }
            if (selectionIsShareable) {
                actions.add(ExplorerActionBarItem.Directory.Share())
            }

            // Compress works on any real (non-archive) selection.
            val allRealPaths = selectionState.selectedItems.all {
                it is ExplorerItem.Lookup && it.path !is ArchivePath
            }
            if (allRealPaths && isWritable) {
                actions.add(ExplorerActionBarItem.Directory.Compress())
            }

            // Extract appears when every selected item is a browsable archive file. Entries that are
            // themselves inside an archive (nested archives) are excluded - the archive service can't
            // open an ArchivePath as a container, so extraction would just fail.
            val allArchives = selectionState.selectedItems.isNotEmpty() &&
                selectionState.selectedItems.all {
                    it is ExplorerItem.RegularFile &&
                        it.path !is ArchivePath &&
                        ArchiveFormat.fromFileName(it.lookup.name) != null
                }
            if (allArchives) {
                actions.add(ExplorerActionBarItem.Directory.Extract())
            }

            actions.add(ExplorerActionBarItem.Common.Info())

            addFavoritesSelectionAction(actions, selectionState)
        } else {
            actions.add(
                ExplorerActionBarItem.Directory.Create(
                    isEnabled = isWritable,
                )
            )

            actions.add(ExplorerActionBarItem.Common.Refresh())

            if (directory != null) {
                val isFavorite = favoritesRepo.isFavorite(directory.path)
                actions.add(
                    ExplorerActionBarItem.Directory.ToggleFavoriteCurrent(
                        path = directory.path,
                        isFavorite = isFavorite,
                    )
                )
            }

            // Sort/filter/view-style have no effect on an empty folder.
            if (!isEmpty) {
                actions.add(ExplorerActionBarItem.Common.Sort())
                actions.add(ExplorerActionBarItem.Common.Filter())

                actions.add(ExplorerActionBarItem.Common.UpdateViewStyle(viewStyle.toggled()))
            }
        }

        return actions
    }

    private fun addFavoritesSelectionAction(
        actions: MutableList<ExplorerActionBarItem>,
        selectionState: ExplorerSelectionState,
    ) {
        val lookups = selectionState.selectedItems.filterIsInstance<ExplorerItem.Lookup>()
        if (lookups.isEmpty()) return
        val paths = lookups.map { it.path }
        val allFavorited = paths.all { favoritesRepo.isFavorite(it) }
        if (allFavorited) {
            actions.add(ExplorerActionBarItem.Common.RemoveFromFavorites(paths))
        } else {
            val toAdd = paths.filterNot { favoritesRepo.isFavorite(it) }
            actions.add(ExplorerActionBarItem.Common.AddToFavorites(items = toAdd))
        }
    }
}