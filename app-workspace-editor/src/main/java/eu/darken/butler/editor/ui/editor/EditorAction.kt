package eu.darken.butler.editor.ui.editor

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.ContentCopy
import androidx.compose.material.icons.twotone.ContentCut
import androidx.compose.material.icons.twotone.ContentPaste
import androidx.compose.material.icons.twotone.Delete
import androidx.compose.material.icons.twotone.FormatListNumbered
import androidx.compose.material.icons.twotone.Search
import androidx.compose.material.icons.twotone.SelectAll
import androidx.compose.ui.graphics.vector.ImageVector
import eu.darken.butler.common.ca.CaString
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.editor.R
import eu.darken.butler.workspace.ui.actions.WorkspaceAction

/**
 * Sealed interface for workspace-level domain actions in the Editor.
 * These represent higher-level operations that may be exposed in action bars or menus.
 *
 * Currently minimal as the editor is primarily focused on single-file editing,
 * but can be extended for features like copy/paste, multi-file editing, etc.
 */
sealed interface EditorAction : WorkspaceAction {
    override val icon: ImageVector
    override val label: CaString
    override val isVisible: Boolean get() = true
    override val isEnabled: Boolean get() = true
    override val isDestructive: Boolean get() = false
    override val group: WorkspaceAction.Group get() = WorkspaceAction.Group.PRIMARY
    override val badge: Boolean get() = false

    /**
     * Copy selected text to clipboard
     */
    data object Copy : EditorAction {
        override val icon = Icons.TwoTone.ContentCopy
        override val label = eu.darken.butler.common.R.string.general_copy_action.toCaString()
    }

    /**
     * Cut selected text to clipboard
     */
    data object Cut : EditorAction {
        override val icon = Icons.TwoTone.ContentCut
        override val label = eu.darken.butler.common.R.string.general_cut_action.toCaString()
    }

    /**
     * Paste text from clipboard
     */
    data object Paste : EditorAction {
        override val icon = Icons.TwoTone.ContentPaste
        override val label = eu.darken.butler.common.R.string.general_paste_action.toCaString()
    }

    /**
     * Delete selected text
     */
    data object Delete : EditorAction {
        override val icon = Icons.TwoTone.Delete
        override val label = R.string.editor_action_delete.toCaString()
        override val isDestructive = true
    }

    /**
     * Select all text in document
     */
    data object SelectAll : EditorAction {
        override val icon = Icons.TwoTone.SelectAll
        override val label = R.string.editor_action_select_all.toCaString()
        override val group = WorkspaceAction.Group.SECONDARY
    }

    /**
     * Go to a specific line number
     */
    data object GoToLine : EditorAction {
        override val icon = Icons.TwoTone.FormatListNumbered
        override val label = R.string.editor_action_go_to_line.toCaString()
        override val group = WorkspaceAction.Group.SECONDARY
    }

    /**
     * Search for text in the document
     */
    data object Search : EditorAction {
        override val icon = Icons.TwoTone.Search
        override val label = R.string.editor_action_search.toCaString()
        override val group = WorkspaceAction.Group.SECONDARY
    }
}
