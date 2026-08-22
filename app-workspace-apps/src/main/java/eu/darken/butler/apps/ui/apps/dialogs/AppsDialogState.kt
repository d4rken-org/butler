package eu.darken.butler.apps.ui.apps.dialogs

import eu.darken.butler.apps.core.AppPath
import eu.darken.butler.apps.core.engine.AppItem
import eu.darken.butler.workspace.contracts.apps.AppTag
import eu.darken.butler.workspace.contracts.apps.SortSettings

sealed interface AppsDialogState {

    data object None : AppsDialogState

    data class AppDetails(
        val app: AppItem,
        val availablePaths: List<AppPath> = emptyList(),
    ) : AppsDialogState

    data class FilterOptions(
        val availableTags: List<AppTag>,
    ) : AppsDialogState

    data class SortOptions(
        val currentSortSettings: SortSettings,
        val sizesAvailable: Boolean = true,
    ) : AppsDialogState

    data class ConfirmDisable(
        val apps: List<AppItem>,
    ) : AppsDialogState

    data class ConfirmEnable(
        val apps: List<AppItem>,
    ) : AppsDialogState

    data class ConfirmUninstall(
        val apps: List<AppItem>,
    ) : AppsDialogState

    data class ConfirmClearData(
        val apps: List<AppItem>,
    ) : AppsDialogState
}
