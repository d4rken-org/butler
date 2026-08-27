package eu.darken.butler.explorer.ui.explorer.actions

import eu.darken.butler.explorer.core.ExplorerViewStyle
import eu.darken.butler.explorer.core.engine.ExplorerLocation
import eu.darken.butler.explorer.ui.explorer.util.ExplorerSelectionState
import javax.inject.Inject

class DefaultActionProvider @Inject constructor(
    private val homeProvider: HomeActionProvider,
    private val deviceProvider: DeviceActionProvider,
    private val networkProvider: NetworkActionProvider,
    private val directoryProvider: DirectoryActionProvider,
    private val trashActionProvider: TrashActionProvider,
) : ExplorerActionProvider {

    override fun getActions(
        location: ExplorerLocation,
        selectionState: ExplorerSelectionState,
        viewStyle: ExplorerViewStyle,
        trashEnabled: Boolean,
    ): List<ExplorerActionBarItem> {
        val provider = when (location) {
            is ExplorerLocation.Home -> homeProvider
            is ExplorerLocation.Device -> deviceProvider
            is ExplorerLocation.Network -> networkProvider
            is ExplorerLocation.Directory -> directoryProvider
            is ExplorerLocation.Trash -> trashActionProvider
        }

        return provider.getActions(location, selectionState, viewStyle, trashEnabled)
    }
}