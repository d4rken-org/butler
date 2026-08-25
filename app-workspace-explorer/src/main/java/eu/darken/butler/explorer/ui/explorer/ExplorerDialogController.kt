package eu.darken.butler.explorer.ui.explorer

import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.flow.SingleEventFlow
import eu.darken.butler.explorer.core.FilterState
import eu.darken.butler.explorer.ui.explorer.dialogs.ExplorerDialogEvent
import eu.darken.butler.explorer.ui.explorer.dialogs.ExplorerDialogState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlin.uuid.Uuid

/**
 * Owns the Explorer page's single dialog slot (last-write-wins) and the dialog event channel.
 * Extracted from the ViewModel for isolated testing, mirroring the Editor controller pattern.
 */
class ExplorerDialogController(
    private val filterState: () -> FilterState,
    private val useRegexPatterns: () -> Boolean,
    private val clearSelection: () -> Unit,
    private val tag: String,
) {

    private val dialogStateFlow = MutableStateFlow<ExplorerDialogState>(ExplorerDialogState.None)
    val state: StateFlow<ExplorerDialogState> = dialogStateFlow

    val events = SingleEventFlow<ExplorerDialogEvent>()

    fun show(dialog: ExplorerDialogState) {
        dialogStateFlow.value = dialog
    }

    fun dismiss() {
        dialogStateFlow.value = ExplorerDialogState.None
    }

    /**
     * Atomically dismisses [expected] if it is the dialog currently showing. Callers that act on a
     * dialog's confirmation use this instead of a check followed by [dismiss]: two rapid taps would
     * both pass a separate check and run the action twice.
     *
     * @return true if this call is the one that dismissed [expected].
     */
    fun dismissIfCurrent(expected: ExplorerDialogState): Boolean =
        dialogStateFlow.compareAndSet(expected, ExplorerDialogState.None)

    /**
     * Applies [block] to the open network info sheet, and does nothing at all unless the sheet
     * showing right now is still the one for [locationId].
     *
     * Everything an open sheet loads in the background lands here rather than through [show]: two
     * async completions would otherwise overwrite each other, write onto a stale item, or resurrect
     * a sheet the user already dismissed. [MutableStateFlow.update] makes the check and the write
     * one step.
     */
    fun updateSingleNetwork(
        locationId: Uuid,
        block: (ExplorerDialogState.ItemInfo.InfoContext.SingleNetwork) ->
        ExplorerDialogState.ItemInfo.InfoContext.SingleNetwork,
    ) {
        dialogStateFlow.update { current ->
            val context = (current as? ExplorerDialogState.ItemInfo)?.context
                as? ExplorerDialogState.ItemInfo.InfoContext.SingleNetwork
            if (context == null || context.locationId != locationId) {
                current
            } else {
                ExplorerDialogState.ItemInfo(block(context))
            }
        }
    }

    fun current(): ExplorerDialogState = dialogStateFlow.value

    fun handle(event: ExplorerDialogEvent) {
        log(tag) { "handleDialogEvent($event)" }
        when (event) {
            is ExplorerDialogEvent.ShowCreateItem -> {
                dialogStateFlow.value = ExplorerDialogState.CreateItem
            }
            is ExplorerDialogEvent.ShowDeleteConfirmation -> {
                dialogStateFlow.value = ExplorerDialogState.DeleteConfirmation(
                    items = event.items,
                    initialPermanentDelete = event.initialPermanentDelete,
                )
            }
            is ExplorerDialogEvent.ShowRename -> {
                dialogStateFlow.value = ExplorerDialogState.Rename(event.item)
                clearSelection()
            }
            is ExplorerDialogEvent.ShowFilterOptions -> {
                val filterState = filterState()
                dialogStateFlow.value = ExplorerDialogState.FilterOptions(
                    includePattern = filterState.includePattern,
                    excludePattern = filterState.excludePattern,
                    fileTypeFilter = filterState.fileTypeFilter,
                    useRegexPatterns = useRegexPatterns(),
                )
            }
            is ExplorerDialogEvent.Dismiss -> {
                dialogStateFlow.value = ExplorerDialogState.None
            }
        }
    }
}
