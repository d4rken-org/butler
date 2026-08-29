package eu.darken.butler.searcher.ui.search.util

import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.actions.PathActionIssue
import eu.darken.butler.permissions.core.PathRequirements
import eu.darken.butler.searcher.core.SearchItem
import eu.darken.butler.searcher.core.SearchTemplate
import eu.darken.butler.searcher.core.history.SearchHistory
import eu.darken.butler.searcher.ui.search.dialogs.SearchSortOptionsResult
import eu.darken.butler.workspace.contracts.searcher.FilterCondition
import eu.darken.butler.workspace.contracts.searcher.SearchTarget
import eu.darken.butler.workspace.core.clipboard.ClipboardClip
import eu.darken.butler.workspace.core.operations.Operation

/**
 * Sealed interface representing all page-level actions in the Searcher workspace.
 * This consolidates the various callbacks from SearcherWorkspacePage into a single type-safe hierarchy.
 *
 * Note: This is distinct from [SearcherActionBarItem] which represents workspace-level domain operations
 * (Copy, Cut, Delete, etc.). [SearcherPageAction] encompasses all UI interactions including search,
 * state management, and delegation to workspace actions.
 */
sealed interface SearcherPageAction {

    /**
     * Search query and execution actions
     */
    sealed interface Search : SearcherPageAction {
        /**
         * Update the filename pattern query text
         */
        data class UpdateFilenameQuery(val text: String) : Search

        /**
         * Update the content pattern query text
         */
        data class UpdateContentQuery(val text: String) : Search

        /**
         * Perform search with current query (auto-triggered)
         */
        data object Perform : Search

        /**
         * Perform explicit search with history save
         */
        data object Explicit : Search

        /**
         * Cancel the current search operation
         */
        data object Cancel : Search

        /**
         * Clear search results and reset query
         */
        data object ClearResults : Search
    }

    /**
     * Search option toggles - per-field options for filename and content patterns
     */
    sealed interface Options : SearcherPageAction {
        // Filename pattern options
        data object ToggleFilenameCaseSensitive : Options
        data object ToggleFilenameWholeWord : Options
        data object ToggleFilenameRegex : Options

        // Content pattern options
        data object ToggleContentCaseSensitive : Options
        data object ToggleContentWholeWord : Options
        data object ToggleContentRegex : Options

        // Content search toggle (shows/hides content field)
        data object ToggleContentSearch : Options
    }

    /**
     * Search target/path management
     */
    sealed interface Targets : SearcherPageAction {
        /**
         * Remove a search target
         */
        data class Remove(val target: SearchTarget) : Targets

        /**
         * Toggle enabled state of a search target
         */
        data class ToggleEnabled(val target: SearchTarget) : Targets

        /**
         * Open path picker to add search targets
         */
        data object OpenPicker : Targets

        /**
         * Add a MediaStore media collection as a search target
         */
        data class AddMediaStore(val collection: SearchTarget.MediaStore.Collection) : Targets

        /**
         * Add default search paths (all public storage volumes)
         */
        data object AddDefaultPaths : Targets
    }

    /**
     * Search history actions
     */
    sealed interface History : SearcherPageAction {
        /**
         * Show clear history confirmation dialog
         */
        data object ShowClearDialog : History

        /**
         * Clear all search history
         */
        data object Clear : History

        /**
         * Remove a specific history item
         */
        data class Remove(val item: SearchHistory.SearchHistoryItem) : History

        /**
         * Restore search from history item
         */
        data class Click(val item: SearchHistory.SearchHistoryItem) : History
    }

    /**
     * Search template actions
     */
    sealed interface Templates : SearcherPageAction {
        /**
         * Apply a search template and execute search
         */
        data class Apply(val template: SearchTemplate) : Templates
    }

    /**
     * Search filter actions - condition-based editors and management
     */
    sealed interface Filter : SearcherPageAction {
        /**
         * Open size condition editor (for adding new)
         */
        data object OpenSizeConditionEditor : Filter

        /**
         * Open date condition editor (for adding new)
         */
        data object OpenDateConditionEditor : Filter

        /**
         * Open type condition editor (for adding new)
         */
        data object OpenTypeConditionEditor : Filter

        /**
         * Add a new filter condition
         */
        data class AddCondition(val condition: FilterCondition) : Filter

        /**
         * Remove a filter condition
         */
        data class RemoveCondition(val condition: FilterCondition) : Filter

        /**
         * Edit an existing filter condition (opens editor with current values)
         */
        data class EditCondition(val condition: FilterCondition) : Filter
    }

    /**
     * Search result and selection actions
     */
    sealed interface Results : SearcherPageAction {
        /**
         * Click on a search result
         */
        data class Click(val item: SearchItem) : Results

        /**
         * Enter selection mode with initial item
         */
        data class EnterSelectionMode(val item: SearchItem) : Results

        /**
         * Toggle selection state of an item
         */
        data class ToggleSelection(val item: SearchItem) : Results

        /**
         * Replace the selection, e.g. with the range a drag has swept over
         */
        data class SetSelection(val resultIds: Set<String>) : Results

        /**
         * Exit selection mode
         */
        data object ExitSelectionMode : Results

        /**
         * Hide quick actions sheet
         */
        data object HideQuickActions : Results
    }

    /**
     * Clipboard management actions
     */
    sealed interface Clipboard : SearcherPageAction {
        /**
         * Click on a clipboard entry
         */
        data class ClickEntry(val clip: ClipboardClip) : Clipboard

        /**
         * Remove a clipboard entry
         */
        data class RemoveEntry(val clip: ClipboardClip) : Clipboard

        /**
         * Clear all clipboard entries
         */
        data object ClearAll : Clipboard

        /**
         * Open a new Explorer workspace at the clip's source location
         */
        data class NavigateToSource(val clip: ClipboardClip) : Clipboard

        /**
         * Open a new Explorer workspace at the common parent of the clip's paths
         */
        data class OpenInExplorer(val clip: ClipboardClip) : Clipboard

        /**
         * Copy plain text (e.g. a path) to the system clipboard
         */
        data class CopyText(val text: String) : Clipboard
    }

    /**
     * Operation management actions
     */
    sealed interface Operations : SearcherPageAction {
        /**
         * Cancel a running operation
         */
        data class Cancel(val id: Operation.Id) : Operations

        /**
         * Dismiss an operation from the list
         */
        data class Dismiss(val id: Operation.Id) : Operations

        /**
         * Clear all completed operations
         */
        data object ClearCompleted : Operations

        /**
         * Share the error report of a failed operation
         */
        data class ShareError(val id: Operation.Id) : Operations

        /**
         * Request the conflict sheet for a waiting operation
         * (sheet display is driven by issue state; see ViewModel)
         */
        data class ShowConflict(val id: Operation.Id) : Operations
    }

    /**
     * Dialog confirmations and dismissal
     */
    sealed interface Dialogs : SearcherPageAction {
        /**
         * Dismiss the currently shown dialog
         */
        data object Dismiss : Dialogs

        /**
         * Delete confirmed for the given paths
         */
        data class DeleteConfirmed(
            val paths: Set<APath<*>>,
            val forcePermDelete: Boolean = false,
        ) : Dialogs

        /**
         * Sort options confirmed
         */
        data class SortOptionsConfirmed(val result: SearchSortOptionsResult) : Dialogs

        /**
         * Clear-history confirmed
         */
        data object ClearHistoryConfirmed : Dialogs
    }

    /**
     * Operation issue/conflict resolution
     */
    sealed interface Issues : SearcherPageAction {
        /**
         * Resolve the currently pending operation issue
         */
        data class Resolve(val resolution: PathActionIssue.Resolution) : Issues
    }

    /**
     * Setup and permissions
     */
    sealed interface Setup : SearcherPageAction {
        /**
         * Open setup screen for permissions
         */
        data class Open(val requirements: PathRequirements) : Setup
    }

    /**
     * Error handling
     */
    sealed interface Error : SearcherPageAction {
        /**
         * Offer to share this error; [targetPath] names the search target it belongs to when the
         * site knows one.
         */
        data class Share(val error: Throwable, val targetPath: String? = null) : Error

        /** The user consented to the offered share. */
        data object ConfirmShare : Error

        /** The user declined the offered share. */
        data object DismissShare : Error
    }

    /**
     * Visibility of the page's overlays.
     *
     * Overlays are composed as a sibling of the page, so their visibility has to live in the
     * ViewModel — a `remember` in the page would be a different instance from the one they read.
     */
    sealed interface Overlays : SearcherPageAction {
        data object ShowTemplates : Overlays
        data object DismissTemplates : Overlays
        data object ShowAccessErrors : Overlays
        data object DismissAccessErrors : Overlays
        data class ShowOperationDetails(val id: Operation.Id) : Overlays
        data object DismissOperationDetails : Overlays

        /** Cancel confirmation, raised from the operations bar and rendered at pane level. */
        data class RequestCancelOperation(val id: Operation.Id) : Overlays
        data object DismissCancelOperation : Overlays

        /** Failure of a single search target, surfaced from the progress card. */
        data class ShowTargetError(val path: String, val error: Throwable) : Overlays
        data object DismissTargetError : Overlays
    }

    /**
     * Wrapper for workspace-level actions
     * Delegates to existing [SearcherActionBarItem] for domain operations
     */
    data class WorkspaceAction(val action: SearcherActionBarItem) : SearcherPageAction
}