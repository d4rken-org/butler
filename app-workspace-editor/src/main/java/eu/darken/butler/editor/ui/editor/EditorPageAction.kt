package eu.darken.butler.editor.ui.editor

import eu.darken.butler.common.files.APath
import eu.darken.butler.editor.core.engine.TextPosition

/**
 * Sealed interface representing all page-level actions in the Editor workspace.
 * This consolidates the various callbacks from EditorWorkspacePage into a single type-safe hierarchy.
 *
 * Note: This is distinct from [EditorAction] which represents workspace-level domain operations.
 * [EditorPageAction] encompasses all UI interactions including file operations, editing, and navigation.
 */
sealed interface EditorPageAction {

    /**
     * File operations
     */
    sealed interface File : EditorPageAction {
        /**
         * Launch file picker to open a file
         */
        data object LaunchPicker : File

        /**
         * Open a specific file
         */
        data class Open(val path: APath<*>) : File

        /**
         * Save the current file
         */
        data object Save : File

        /**
         * Close the current file
         */
        data object Close : File
    }

    /**
     * Text editing operations
     */
    sealed interface Edit : EditorPageAction {
        /**
         * Insert text at current cursor position
         */
        data class InsertText(val text: String) : Edit

        /**
         * Delete the current selection
         */
        data object DeleteSelection : Edit

        /**
         * Delete characters at cursor position (backspace)
         */
        data class DeleteAtCursor(val count: Int) : Edit

        /**
         * Copy selected text to clipboard
         */
        data object Copy : Edit

        /**
         * Cut selected text to clipboard (copy + delete)
         */
        data object Cut : Edit

        /**
         * Paste clipboard content at cursor or replace selection
         */
        data object Paste : Edit

        /**
         * Select all text in the document
         */
        data object SelectAll : Edit

        /**
         * Undo last change
         */
        data object Undo : Edit

        /**
         * Redo last undone change
         */
        data object Redo : Edit
    }

    /**
     * Navigation and cursor operations
     */
    sealed interface Navigation : EditorPageAction {
        /**
         * Set cursor position
         */
        data class SetCursor(val position: TextPosition) : Navigation

        /**
         * Set text selection range
         */
        data class SetSelection(val start: TextPosition, val end: TextPosition) : Navigation

        /**
         * Clear current selection
         */
        data class ClearSelection(val cursorPosition: TextPosition) : Navigation

        /**
         * Search for text in the document
         */
        data class Search(val query: String) : Navigation

        /**
         * Go to a specific line number
         */
        data class GoToLine(val lineNumber: Int) : Navigation

        /**
         * Update the visible range of lines
         */
        data class UpdateVisibleRange(val startLine: Int, val endLine: Int) : Navigation
    }

    /**
     * Error handling
     */
    sealed interface Error : EditorPageAction {
        /**
         * Clear the current error
         */
        data object Clear : Error
    }
}
