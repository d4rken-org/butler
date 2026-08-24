package eu.darken.butler.explorer.ui.explorer

import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.explorer.core.ExplorerWorkspace
import eu.darken.butler.explorer.core.engine.ExplorerItem
import eu.darken.butler.explorer.core.engine.ExplorerLocation
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
    private val currentLocationId: () -> String?,
    private val navigate: (ExplorerItem) -> Unit,
    private val doLaunch: (suspend CoroutineScope.() -> Unit) -> Unit,
    private val tag: String,
) {

    private val selectedItemsFlow = MutableStateFlow<Set<ExplorerItem>>(emptySet())
    val selectedItems: StateFlow<Set<ExplorerItem>> = selectedItemsFlow

    /**
     * Guards every read-modify-write on the selection. User input arrives on the UI thread while
     * [pruneAgainst] runs on a background dispatcher - unguarded, a prune computed from a stale
     * read would overwrite a selection made in the meantime. The flow stays the publication
     * mechanism, the lock only covers the compute-then-write sections (reentrant, no suspending
     * work inside).
     */
    private val selectionLock = Any()

    /** Location the current selection was made in, so a listing of another location can't prune it. */
    private var selectionLocationId: String? = null

    /** Single-select picker modes cap the selection at one item, normal browsing is unrestricted. */
    private val allowsMultiSelect: Boolean
        get() = pickerConfig()?.selection?.isMultiSelect != false

    fun toggle(item: ExplorerItem) {
        if (!item.isSelectable()) {
            log(tag, WARN) { "toggleItemSelection($item) is not selectable" }
            return
        }
        val multiSelect = allowsMultiSelect

        synchronized(selectionLock) {
            val currentSelection = selectedItemsFlow.value

            // Single-select picker modes behave like radio buttons
            val newSelection = if (!multiSelect) {
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
            updateSelection(newSelection)
        }
    }

    fun set(items: Set<ExplorerItem>) {
        if (!allowsMultiSelect && items.size > 1) {
            log(tag, WARN) { "set(${items.size} items) rejected, picker is single-select" }
            return
        }
        updateSelection(items)
    }

    fun clear() {
        updateSelection(emptySet())
    }

    fun selectAll() = doLaunch {
        if (!allowsMultiSelect) {
            log(tag, WARN) { "selectAll() rejected, picker is single-select" }
            return@doLaunch
        }
        val selectable = selectableItems()
        synchronized(selectionLock) { updateSelection(selectable) }
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
        synchronized(selectionLock) { updateSelection(selectedItemsFlow.value + folders) }
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
        synchronized(selectionLock) { updateSelection(selectedItemsFlow.value + files) }
    }

    private fun updateSelection(items: Set<ExplorerItem>) = synchronized(selectionLock) {
        selectedItemsFlow.value = items
        selectionLocationId = if (items.isEmpty()) null else currentLocationId()
    }

    /**
     * Re-anchors the selection to a freshly loaded listing: selected items are re-projected onto
     * their current instances (metadata passes replace them) and vanished items are dropped.
     *
     * Skipped while the location is still loading (the listing is incomplete then) and for listings
     * of another location than the selection was made in. The RAW listing is used on purpose -
     * filtering hides items, it must not unselect them.
     */
    fun pruneAgainst(location: ExplorerLocation?) {
        synchronized(selectionLock) {
            val selected = selectedItemsFlow.value
            if (selected.isEmpty()) return
            if (location == null || location.isLoading) return
            val items = location.items ?: return
            if (location.locationId != selectionLocationId) return

            val itemsById = items.associateBy { it.id }
            val pruned = selected.mapNotNull { itemsById[it.id] }.toSet()
            if (pruned == selected) return

            log(tag) { "pruneAgainst(): ${selected.size} selected -> ${pruned.size} still present" }
            selectedItemsFlow.value = pruned
        }
    }

    fun onItemClick(item: ExplorerItem) = doLaunch {
        log(tag) { "onItemClick($item)" }
        val workspace = workspace()
        val pickerConfig = workspace.pickerConfig

        when {
            // A location that needs a sign-in cannot be selected or opened, so every tap on it goes
            // to the form that fixes that (navigate() shows it, see ExplorerNavigationController).
            item.needsSignIn() -> {
                navigate(item)
            }
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

        // While a selection exists the long press belongs to the drag gesture, taps do the selecting.
        if (selectedItemsFlow.value.isNotEmpty()) {
            log(tag) { "onItemLongClick($item) ignored, selection is active" }
            return
        }

        if (item.needsSignIn()) {
            navigate(item)
            return
        }

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

    private fun ExplorerItem.needsSignIn(): Boolean = this is ExplorerItem.Storage.Network &&
        status == ExplorerItem.Storage.Network.Status.SIGN_IN_REQUIRED
}
