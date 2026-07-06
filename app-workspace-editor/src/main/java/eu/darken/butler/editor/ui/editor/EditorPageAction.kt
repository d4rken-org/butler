package eu.darken.butler.editor.ui.editor

import androidx.compose.ui.text.input.TextFieldValue
import eu.darken.butler.editor.core.engine.TextPosition
import eu.darken.butler.editor.ui.editor.text.CursorDirection
import eu.darken.butler.workspace.core.clipboard.ClipboardClip

/**
 * Sealed interface representing all page-level actions in the Editor workspace.
 * This consolidates the various callbacks from EditorWorkspacePage into a single type-safe hierarchy.
 *
 * Note: This is distinct from [eu.darken.butler.editor.ui.editor.elements.EditorActionBarItem] which represents workspace-level domain operations
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

        /** Pick a destination and save the document there */
        data object SaveAs : File

        /** Close the current file */
        data object Close : File

        /** Cancel an in-progress file open operation */
        data object CancelOpen : File

        /** Show the encoding picker dialog */
        data object ShowEncodingPicker : File

        /** Reopen the current file decoding it with the given charset */
        data class ReopenWithEncoding(val charsetName: String) : File

        /** Dismiss the stale-backup notice for the current file */
        data object DismissBackupNotice : File
    }

    /**
     * Text editing operations (direct text manipulation from editor component)
     */
    sealed interface Edit : EditorPageAction {
        /** Insert text at current cursor position */
        data class InsertText(val text: String) : Edit

        /** Delete characters at cursor position (backspace) */
        data class DeleteAtCursor(val count: Int) : Edit

        /**
         * Replace the [start]..[end] range with [text] and place the cursor at [caret].
         * This is the single-region edit that soft-keyboard input (typing, autocorrect, IME) produces.
         */
        data class ReplaceRange(
            val start: TextPosition,
            val end: TextPosition,
            val text: String,
            val caret: TextPosition,
        ) : Edit

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
        data class GoToLine(val lineNumber: Long) : Navigation

        /** Update the visible range of lines */
        data class UpdateVisibleRange(val startLine: Long, val endLine: Long) : Navigation
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

        /** Dismiss the Close Confirm dialog */
        data object DismissCloseConfirm : Dialog

        /** Confirm closing the file (discard changes) */
        data object ConfirmClose : Dialog

        /** Dismiss the encoding picker dialog */
        data object DismissEncoding : Dialog

        /** Confirm reopening with a new encoding, discarding unsaved changes */
        data object ConfirmEncodingDiscard : Dialog

        /** Dismiss the encoding discard-confirmation dialog */
        data object DismissEncodingDiscard : Dialog

        /** Confirm overwriting an existing file as the Save-As destination */
        data object ConfirmSaveAsOverwrite : Dialog

        /** Dismiss the Save-As overwrite confirmation */
        data object DismissSaveAsOverwrite : Dialog
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
