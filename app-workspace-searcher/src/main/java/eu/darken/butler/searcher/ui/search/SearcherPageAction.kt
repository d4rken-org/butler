package eu.darken.butler.searcher.ui.search

import androidx.compose.ui.text.input.TextFieldValue
import eu.darken.butler.searcher.core.history.SearchHistory
import eu.darken.butler.searcher.core.SearchItem
import eu.darken.butler.searcher.core.SearchTarget
import eu.darken.butler.workspace.core.clipboard.ClipboardClip
import eu.darken.butler.workspace.core.operations.Operation

/**
 * Sealed interface representing all page-level actions in the Searcher workspace.
 * This consolidates the various callbacks from SearcherWorkspacePage into a single type-safe hierarchy.
 *
 * Note: This is distinct from [SearcherAction] which represents workspace-level domain operations
 * (Copy, Cut, Delete, etc.). [SearcherPageAction] encompasses all UI interactions including search,
 * state management, and delegation to workspace actions.
 */
sealed interface SearcherPageAction {

    /**
     * Search query and execution actions
     */
    sealed interface Search : SearcherPageAction {
        /**
         * Update the search query text
         */
        data class UpdateQuery(val query: TextFieldValue) : Search

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
     * Search option toggles
     */
    sealed interface Options : SearcherPageAction {
        /**
         * Toggle case-sensitive search
         */
        data object ToggleCaseSensitive : Options

        /**
         * Toggle whole word matching
         */
        data object ToggleWholeWord : Options

        /**
         * Toggle regex search mode
         */
        data object ToggleRegex : Options
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
    }

    /**
     * Search history actions
     */
    sealed interface History : SearcherPageAction {
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
    }

    /**
     * Setup and permissions
     */
    sealed interface Setup : SearcherPageAction {
        /**
         * Open setup screen for permissions
         */
        data object Open : Setup
    }

    /**
     * Error handling
     */
    sealed interface Error : SearcherPageAction {
        /**
         * Copy error details to clipboard
         */
        data class Copy(val error: Throwable) : Error
    }

    /**
     * Wrapper for workspace-level actions
     * Delegates to existing [SearcherAction] for domain operations
     */
    data class WorkspaceAction(val action: SearcherAction) : SearcherPageAction
}
