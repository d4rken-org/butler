package eu.darken.butler.explorer.ui.explorer.actions

import eu.darken.butler.common.files.APath
import eu.darken.butler.explorer.core.ExplorerViewStyle
import eu.darken.butler.explorer.core.engine.ExplorerLocation
import eu.darken.butler.explorer.ui.explorer.util.ExplorerSelectionState

interface ExplorerActionProvider {

    /**
     * @param favoriteSelection paths selected in the Home screen's favorites section; only the home
     *        provider acts on it, so it is defaulted like [trashEnabled].
     */
    fun getActions(
        location: ExplorerLocation,
        selectionState: ExplorerSelectionState,
        viewStyle: ExplorerViewStyle,
        trashEnabled: Boolean = false,
        favoriteSelection: Set<APath<*>> = emptySet(),
    ): List<ExplorerActionBarItem>

}