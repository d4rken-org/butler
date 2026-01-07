package eu.darken.butler.editor.ui.editor.elements

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.ContentCopy
import androidx.compose.material.icons.twotone.ContentCut
import androidx.compose.material.icons.twotone.ContentPaste
import androidx.compose.material.icons.twotone.Delete
import androidx.compose.material.icons.twotone.FormatListNumbered
import androidx.compose.material.icons.twotone.Search
import androidx.compose.material.icons.twotone.SelectAll
import androidx.compose.ui.graphics.vector.ImageVector
import eu.darken.butler.common.R
import eu.darken.butler.common.ca.CaString
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.workspace.ui.actions.WorkspaceActionBarItem

/**
 * Sealed interface for workspace-level domain actions in the Editor.
 * These represent higher-level operations that may be exposed in action bars or menus.
 *
 * Currently minimal as the editor is primarily focused on single-file editing,
 * but can be extended for features like copy/paste, multi-file editing, etc.
 */
sealed interface EditorActionBarItem : WorkspaceActionBarItem {
    override val icon: ImageVector
    override val label: CaString
    override val isVisible: Boolean get() = true
    override val isEnabled: Boolean get() = true
    override val isDestructive: Boolean get() = false
    override val group: WorkspaceActionBarItem.Group get() = WorkspaceActionBarItem.Group.PRIMARY
    override val badge: Boolean get() = false

    /**
     * Copy selected text to clipboard.
     * Long press copies to Butler clipboard.
     */
    data object Copy : EditorActionBarItem {
        override val icon = Icons.TwoTone.ContentCopy
        override val label = R.string.general_copy_action.toCaString()
        override val supportsLongPress = true
    }

    /**
     * Cut selected text to clipboard.
     * Long press cuts to Butler clipboard.
     */
    data object Cut : EditorActionBarItem {
        override val icon = Icons.TwoTone.ContentCut
        override val label = R.string.general_cut_action.toCaString()
        override val supportsLongPress = true
    }

    /**
     * Paste text from clipboard
     */
    data object Paste : EditorActionBarItem {
        override val icon = Icons.TwoTone.ContentPaste
        override val label = R.string.general_paste_action.toCaString()
    }

    /**
     * Delete selected text
     */
    data object Delete : EditorActionBarItem {
        override val icon = Icons.TwoTone.Delete
        override val label = eu.darken.butler.editor.R.string.editor_action_delete.toCaString()
        override val isDestructive = true
    }

    /**
     * Select all text in document
     */
    data object SelectAll : EditorActionBarItem {
        override val icon = Icons.TwoTone.SelectAll
        override val label = eu.darken.butler.editor.R.string.editor_action_select_all.toCaString()
        override val group = WorkspaceActionBarItem.Group.SECONDARY
    }

    /**
     * Go to a specific line number
     */
    data object GoToLine : EditorActionBarItem {
        override val icon = Icons.TwoTone.FormatListNumbered
        override val label = eu.darken.butler.editor.R.string.editor_action_go_to_line.toCaString()
        override val group = WorkspaceActionBarItem.Group.SECONDARY
    }

    /**
     * Search for text in the document
     */
    data object Search : EditorActionBarItem {
        override val icon = Icons.TwoTone.Search
        override val label = eu.darken.butler.editor.R.string.editor_action_search.toCaString()
        override val group = WorkspaceActionBarItem.Group.SECONDARY
    }
}