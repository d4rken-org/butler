package eu.darken.butler.explorer.ui.explorer

import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.explorer.core.ExplorerWorkspace
import eu.darken.butler.explorer.core.engine.ExplorerItem
import eu.darken.butler.workspace.contracts.explorer.PickerConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Item selection state and click routing: toggle semantics (including the DirectorySingle
 * radio behavior for storage volumes), select-all variants, and the tap/long-press dispatch
 * between selection, picker shortcuts and navigation.
 */
class ExplorerSelectionController(
    private val pickerConfig: () -> PickerConfig?,
    private val workspace: suspend () -> ExplorerWorkspace,
    private val selectableItems: suspend () -> Set<ExplorerItem>,
    private val navigate: (ExplorerItem) -> Unit,
    private val doLaunch: (suspend CoroutineScope.() -> Unit) -> Unit,
    private val tag: String,
) {

    private val selectedItemsFlow = MutableStateFlow<Set<ExplorerItem>>(emptySet())
    val selectedItems: StateFlow<Set<ExplorerItem>> = selectedItemsFlow

    /** Single-select picker modes cap the selection at one item, normal browsing is unrestricted. */
    private val allowsMultiSelect: Boolean
        get() = pickerConfig()?.selection?.isMultiSelect != false

    fun toggle(item: ExplorerItem) {
        if (!item.isSelectable()) {
            log(tag, WARN) { "toggleItemSelection($item) is not selectable" }
            return
        }
        val currentSelection = selectedItemsFlow.value

        // Single-select picker modes behave like radio buttons
        val newSelection = if (!allowsMultiSelect) {
            if (currentSelection.contains(item)) {
                emptySet() // Deselect if clicking the same item
            } else {
                setOf(item) // Replace selection with new item
            }
        } else {
            if (currentSelection.contains(item)) {
                currentSelection - item
            } else {
                currentSelection + item
            }
        }
        selectedItemsFlow.value = newSelection
    }

    fun set(items: Set<ExplorerItem>) {
        if (!allowsMultiSelect && items.size > 1) {
            log(tag, WARN) { "set(${items.size} items) rejected, picker is single-select" }
            return
        }
        selectedItemsFlow.value = items
    }

    fun clear() {
        selectedItemsFlow.value = emptySet()
    }

    fun selectAll() = doLaunch {
        if (!allowsMultiSelect) {
            log(tag, WARN) { "selectAll() rejected, picker is single-select" }
            return@doLaunch
        }
        selectedItemsFlow.value = selectableItems()
    }

    fun selectAllFolders() = doLaunch {
        if (!allowsMultiSelect) {
            log(tag, WARN) { "selectAllFolders() rejected, picker is single-select" }
            return@doLaunch
        }
        val folders = selectableItems().filter { item ->
            item is ExplorerItem.Directory ||
                (item is ExplorerItem.Trash.Nested && item.isDirectory)
        }
        selectedItemsFlow.value += folders
    }

    fun selectAllFiles() = doLaunch {
        if (!allowsMultiSelect) {
            log(tag, WARN) { "selectAllFiles() rejected, picker is single-select" }
            return@doLaunch
        }
        val files = selectableItems().filter { item ->
            item is ExplorerItem.File ||
                (item is ExplorerItem.Trash.Nested && item.isFile)
        }
        selectedItemsFlow.value += files
    }

    fun onItemClick(item: ExplorerItem) = doLaunch {
        log(tag) { "onItemClick($item)" }
        val workspace = workspace()
        val pickerConfig = workspace.pickerConfig

        when {
            // FileMulti mode: tap file to toggle selection
            pickerConfig?.selection is PickerConfig.Selection.FileMulti && item is ExplorerItem.File -> {
                toggle(item)
            }
            // MixedMulti mode: tap file to toggle selection, tap folder to navigate
            pickerConfig?.selection is PickerConfig.Selection.MixedMulti && item is ExplorerItem.File -> {
                toggle(item)
            }
            // SaveAs mode: tap file to prefill the filename field (folders navigate)
            pickerConfig?.selection is PickerConfig.Selection.SaveAs && item is ExplorerItem.File -> {
                workspace.updateSaveAsFilename(item.lookup.name)
            }
            // Selection mode active: toggle selection
            selectedItemsFlow.value.isNotEmpty() -> {
                toggle(item)
            }
            // Normal mode: navigate
            else -> {
                navigate(item)
            }
        }
    }

    fun onItemLongClick(item: ExplorerItem) {
        log(tag) { "onItemLongClick($item)" }
        val pickerConfig = pickerConfig()

        // Enable long-press selection in:
        // - Normal mode (no picker)
        // - Multi-select picker modes
        // - DirectorySingle mode with Storage items (allows selecting storage volumes at Device level)
        val allowLongPress = pickerConfig == null
            || pickerConfig.selection.isMultiSelect
            || (pickerConfig.selection is PickerConfig.Selection.DirectorySingle && item is ExplorerItem.Storage)

        if (allowLongPress) {
            toggle(item)
        }
    }
}
