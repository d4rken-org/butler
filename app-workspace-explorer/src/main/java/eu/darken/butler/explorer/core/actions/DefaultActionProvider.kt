package eu.darken.butler.explorer.core.actions

import eu.darken.butler.explorer.core.engine.ExplorerLocation
import javax.inject.Inject

class DefaultActionProvider @Inject constructor(
    private val homeProvider: HomeActionProvider,
    private val deviceProvider: DeviceActionProvider,
    private val directoryProvider: DirectoryActionProvider,
) : ExplorerActionProvider {
    
    override fun getActions(
        location: ExplorerLocation,
        selectionState: ExplorerActionProvider.SelectionState,
        capabilities: ExplorerActionProvider.LocationCapabilities,
    ): List<ExplorerAction> {
        val provider = when (location) {
            is ExplorerLocation.Home -> homeProvider
            is ExplorerLocation.Device -> deviceProvider
            is ExplorerLocation.Directory -> directoryProvider
        }
        
        return provider.getActions(location, selectionState, capabilities)
    }
}