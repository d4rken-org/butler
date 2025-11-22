package eu.darken.butler.explorer.ui.explorer.actions

import eu.darken.butler.explorer.core.ExplorerViewStyle
import eu.darken.butler.explorer.core.engine.ExplorerLocation
import eu.darken.butler.explorer.ui.explorer.ExplorerSelectionState
import javax.inject.Inject

class DefaultActionProvider @Inject constructor(
    private val homeProvider: HomeActionProvider,
    private val deviceProvider: DeviceActionProvider,
    private val directoryProvider: DirectoryActionProvider,
    private val recycleBinProvider: RecycleBinActionProvider,
) : ExplorerActionProvider {

    override fun getActions(
        location: ExplorerLocation,
        selectionState: ExplorerSelectionState,
        viewStyle: ExplorerViewStyle,
    ): List<ExplorerAction> {
        val provider = when (location) {
            is ExplorerLocation.Home -> homeProvider
            is ExplorerLocation.Device -> deviceProvider
            is ExplorerLocation.Directory -> directoryProvider
            is ExplorerLocation.RecycleBin -> recycleBinProvider
        }

        return provider.getActions(location, selectionState, viewStyle)
    }
}