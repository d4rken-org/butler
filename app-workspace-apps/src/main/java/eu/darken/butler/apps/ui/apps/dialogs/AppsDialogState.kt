package eu.darken.butler.apps.ui.apps.dialogs

import eu.darken.butler.apps.core.AppPath
import eu.darken.butler.apps.core.AppTag
import eu.darken.butler.apps.core.SortSettings
import eu.darken.butler.apps.core.engine.AppItem

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

    data class ConfirmClearCache(
        val apps: List<AppItem>,
    ) : AppsDialogState

    data class ConfirmClearData(
        val apps: List<AppItem>,
    ) : AppsDialogState
}
