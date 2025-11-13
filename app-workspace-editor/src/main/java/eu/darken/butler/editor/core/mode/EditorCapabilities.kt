package eu.darken.butler.editor.core.mode

/**
 * Capabilities available in an editor mode.
 * Different modes have different capabilities.
 */
data class EditorCapabilities(
    /** Can edit content (insert/delete/replace) */
    val canEdit: Boolean = true,

    /** Can search within content */
    val canSearch: Boolean = true,

    /** Can undo/redo operations */
    val canUndo: Boolean = true,

    /** Can jump to a specific line number (text mode) */
    val canGoToLine: Boolean = false,

    /** Can jump to a specific byte offset (hex mode) */
    val canGoToOffset: Boolean = false,

    /** Can display line numbers in gutter */
    val canShowLineNumbers: Boolean = false,
)
