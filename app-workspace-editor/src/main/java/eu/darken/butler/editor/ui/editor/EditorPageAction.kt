package eu.darken.butler.editor.ui.editor

import androidx.compose.ui.text.input.TextFieldValue
import eu.darken.butler.editor.core.engine.TextPosition
import eu.darken.butler.editor.ui.editor.text.CursorDirection
import eu.darken.butler.workspace.core.clipboard.ClipboardClip

/**
 * Sealed interface representing all page-level actions in the Editor workspace.
 * This consolidates the various callbacks from EditorWorkspacePage into a single type-safe hierarchy.
 *
 * Note: This is distinct from [EditorAction] which represents workspace-level domain operations
 * for the action bar (presentation). [EditorPageAction] encompasses all UI interactions including
 * file operations, editing, navigation, search UI, dialogs, and clipboard.
 */
sealed interface EditorPageAction {

    /**
     * File operations
     */
    sealed interface File : EditorPageAction {
        /** Launch file picker to open a file */
        data object LaunchPicker : File

        /** Save the current file */
        data object Save : File

        /** Close the current file */
        data object Close : File

        /** Cancel an in-progress file open operation */
        data object CancelOpen : File
    }

    /**
     * Text editing operations (direct text manipulation from editor component)
     */
    sealed interface Edit : EditorPageAction {
        /** Insert text at current cursor position */
        data class InsertText(val text: String) : Edit

        /** Delete characters at cursor position (backspace) */
        data class DeleteAtCursor(val count: Int) : Edit

        /** Delete character after cursor position (forward delete) */
        data object ForwardDelete : Edit

        /** Undo last change */
        data object Undo : Edit

        /** Redo last undone change */
        data object Redo : Edit
    }

    /**
     * Navigation and cursor operations
     */
    sealed interface Navigation : EditorPageAction {
        /** Move cursor in a direction, optionally extending selection */
        data class MoveCursor(val direction: CursorDirection, val extendSelection: Boolean) : Navigation

        /** Set cursor position */
        data class SetCursor(val position: TextPosition) : Navigation

        /** Set text selection range */
        data class SetSelection(val start: TextPosition, val end: TextPosition) : Navigation

        /** Clear current selection */
        data class ClearSelection(val cursorPosition: TextPosition) : Navigation

        /** Go to a specific line number */
        data class GoToLine(val lineNumber: Int) : Navigation

        /** Update the visible range of lines */
        data class UpdateVisibleRange(val startLine: Int, val endLine: Int) : Navigation
    }

    /**
     * Search UI state operations
     */
    sealed interface Search : EditorPageAction {
        /** Update the search query text field */
        data class UpdateQuery(val query: TextFieldValue) : Search

        /** Toggle case sensitivity in search */
        data object ToggleCaseSensitive : Search

        /** Toggle regex mode in search */
        data object ToggleRegex : Search

        /** Toggle whole word matching in search */
        data object ToggleWholeWord : Search

        /** Navigate to next search result */
        data object NextResult : Search

        /** Navigate to previous search result */
        data object PreviousResult : Search

        /** Close the search bar */
        data object Close : Search
    }

    /**
     * Dialog management operations
     */
    sealed interface Dialog : EditorPageAction {
        /** Dismiss the Go To Line dialog */
        data object DismissGoToLine : Dialog

        /** Dismiss the Search dialog */
        data object DismissSearch : Dialog

        /** Dismiss the Close Confirm dialog */
        data object DismissCloseConfirm : Dialog

        /** Confirm closing the file (discard changes) */
        data object ConfirmClose : Dialog
    }

    /**
     * Butler Clipboard operations
     */
    sealed interface Clipboard : EditorPageAction {
        /** Paste content from a clipboard entry */
        data class Paste(val clip: ClipboardClip) : Clipboard

        /** Remove a clipboard entry */
        data class Remove(val clip: ClipboardClip) : Clipboard

        /** Show info for a clipboard entry */
        data class ShowInfo(val clip: ClipboardClip) : Clipboard

        /** Dismiss the clipboard info sheet */
        data object DismissInfo : Clipboard

        /** Clear all clipboard entries */
        data object Clear : Clipboard
    }

    /**
     * Workspace-level operations
     */
    sealed interface Workspace : EditorPageAction {
        /** Share the workspace error */
        data object ShareError : Workspace

        /** Close the workspace */
        data object Close : Workspace
    }

    /**
     * Error handling
     */
    sealed interface Error : EditorPageAction {
        /** Clear the current error */
        data object Clear : Error
    }
}
