package eu.darken.butler.explorer.ui.explorer.actions

import eu.darken.butler.explorer.core.ExplorerViewStyle
import eu.darken.butler.explorer.core.engine.ExplorerItem
import eu.darken.butler.explorer.core.engine.ExplorerLocation
import eu.darken.butler.explorer.core.favorites.ExplorerFavoritesRepo
import eu.darken.butler.explorer.ui.explorer.util.ExplorerSelectionState
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
        val isWritable = (directory?.info?.isWritable ?: false)

        if (selectionState.isSelectionMode) {
            if (!selectionState.isAllSelected) {
                actions.add(ExplorerActionBarItem.Directory.SelectAll)
            }

            actions.add(ExplorerActionBarItem.Directory.OpenInNewTabs())

            if (selectionState.selectionCount == 1) {
                actions.add(ExplorerActionBarItem.Directory.Rename())
            }

            actions.add(ExplorerActionBarItem.Directory.Copy())

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

            if (selectionState.selectedItems.all { it is ExplorerItem.File }) {
                actions.add(ExplorerActionBarItem.Directory.Share())
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

            actions.add(ExplorerActionBarItem.Common.Sort())
            actions.add(ExplorerActionBarItem.Common.Filter())

            val toggledViewStyle = when (viewStyle) {
                is ExplorerViewStyle.List -> ExplorerViewStyle.Grid()
                is ExplorerViewStyle.Grid -> ExplorerViewStyle.List()
            }
            actions.add(ExplorerActionBarItem.Common.UpdateViewStyle(toggledViewStyle))
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