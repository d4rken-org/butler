package eu.darken.butler.apps.ui.apps

import androidx.compose.ui.text.input.TextFieldValue
import eu.darken.butler.apps.core.engine.AppItem
import eu.darken.butler.apps.ui.apps.elements.AppsActionBarItem
import eu.darken.butler.common.pkgs.features.InstallId
import eu.darken.butler.workspace.contracts.apps.AppTag
import eu.darken.butler.workspace.contracts.apps.SortSettings
import eu.darken.butler.workspace.contracts.apps.TagFilterConfig

/**
 * Sealed interface representing all page-level actions in the Apps workspace.
 * This consolidates the various callbacks from AppsWorkspacePage into a single type-safe hierarchy.
 *
 * Note: This is distinct from [eu.darken.butler.apps.ui.apps.elements.AppsActionBarItem] which represents workspace-level action bar
 * button/chip definitions. [AppsPageAction] encompasses all UI interactions.
 */
sealed interface AppsPageAction {

    /**
     * Workspace lifecycle actions
     */
    sealed interface Workspace : AppsPageAction {
        data object ShareError : Workspace
        data object Close : Workspace
    }

    /**
     * Search/filter query actions
     */
    sealed interface Search : AppsPageAction {
        data class UpdateQuery(val query: TextFieldValue) : Search
    }

    /**
     * Filter chip actions (inline filter management)
     */
    sealed interface Filter : AppsPageAction {
        data object OpenDialog : Filter
        data class RemoveTag(val tag: AppTag, val isExcluded: Boolean) : Filter
    }

    /**
     * App list interaction actions
     */
    sealed interface Apps : AppsPageAction {
        data object Refresh : Apps
        data class Click(val app: AppItem) : Apps
    }

    /**
     * Selection management actions
     */
    sealed interface Selection : AppsPageAction {
        data object Clear : Selection

        /** Replace the selection, e.g. with the range a drag has swept over */
        data class SetSelection(val installIds: Set<InstallId>) : Selection
        data object SelectUserApps : Selection
        data object SelectSystemApps : Selection
    }

    /**
     * Dialog management actions
     */
    sealed interface Dialog : AppsPageAction {
        data object Dismiss : Dialog
        data class ApplyFilter(val config: TagFilterConfig) : Dialog
        data class ApplySort(val settings: SortSettings) : Dialog
        data class ConfirmEnable(val apps: List<AppItem>) : Dialog
        data class ConfirmDisable(val apps: List<AppItem>) : Dialog
        data class ConfirmUninstall(val apps: List<AppItem>) : Dialog
        data class ConfirmClearData(val apps: List<AppItem>) : Dialog
        data object OpenSizeSetup : Dialog
    }

    /**
     * Wrapper for action bar item clicks.
     * Delegates to existing [eu.darken.butler.apps.ui.apps.elements.AppsActionBarItem] for domain operations.
     */
    data class ActionBarClick(val item: AppsActionBarItem) : AppsPageAction
}
