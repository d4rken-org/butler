package eu.darken.butler.apps.ui.apps.dialogs

import eu.darken.butler.apps.core.engine.AppItem
import eu.darken.butler.apps.core.engine.AppsState
import eu.darken.butler.apps.core.engine.SortSettings

sealed interface AppsDialogState {

    data object None : AppsDialogState

    data class AppDetails(val app: AppItem) : AppsDialogState

    data class FilterOptions(
        val currentFilter: AppsState.FilterConfig,
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
