package eu.darken.butler.explorer.ui.explorer

import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.files.TextFileDetector
import eu.darken.butler.common.files.extensions.isDirectory
import eu.darken.butler.explorer.core.engine.ExplorerItem
import eu.darken.butler.explorer.ui.explorer.dialogs.ExplorerDialogState
import eu.darken.butler.workspace.contracts.explorer.ExplorerArguments
import eu.darken.butler.workspace.contracts.viewer.ViewerArguments
import eu.darken.butler.workspace.core.OpenInNewTabsUseCase
import eu.darken.butler.workspace.core.OpenSelectionMode
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.WorkspaceAction
import eu.darken.butler.workspace.core.WorkspaceRemote
import eu.darken.butler.workspace.core.createAndFocus
import kotlinx.coroutines.CoroutineScope

/**
 * The selection bar's "Open": snapshots the selection into the chooser and opens exactly that
 * snapshot in the picked mode, whatever the selection has become in the meantime.
 */
class ExplorerOpenSelectionController(
    private val workspaceId: Workspace.Id,
    private val selectedItems: () -> Set<ExplorerItem>,
    private val orderSelection: suspend (Collection<ExplorerItem>) -> List<ExplorerItem>,
    private val dialogs: ExplorerDialogController,
    private val workspaceRemote: WorkspaceRemote,
    private val openInNewTabsUseCase: OpenInNewTabsUseCase,
    private val clearSelection: () -> Unit,
    private val doLaunch: (suspend CoroutineScope.() -> Unit) -> Unit,
    private val tag: String,
) {

    suspend fun showChooser() {
        val selected = selectedItems()
        log(tag) { "showChooser(): ${selected.size} items" }
        if (selected.isEmpty()) return

        val items = orderSelection(selected)
        val viewerModesAvailable = items.isNotEmpty() && items.all { it is ExplorerItem.File }
        dialogs.show(
            ExplorerDialogState.OpenSelection(
                items = items,
                viewerPaths = if (viewerModesAvailable) {
                    items.filterIsInstance<ExplorerItem.File>().map { it.lookup.lookedUp }
                } else {
                    emptyList()
                },
                viewerModesAvailable = viewerModesAvailable,
            )
        )
    }

    fun onModeSelected(state: ExplorerDialogState.OpenSelection, mode: OpenSelectionMode) {
        if (!dialogs.dismissIfCurrent(state)) {
            log(tag, WARN) { "onModeSelected($mode): chooser is no longer showing" }
            return
        }
        log(tag) { "onModeSelected($mode): ${state.items.size} items" }

        doLaunch {
            when (mode) {
                OpenSelectionMode.VIEW_HERE,
                OpenSelectionMode.VIEW_IN_TAB -> openViewer(state, mode)

                OpenSelectionMode.EACH_IN_TAB -> openEachInTab(state.items)
            }
        }
    }

    private suspend fun openViewer(state: ExplorerDialogState.OpenSelection, mode: OpenSelectionMode) {
        if (!state.viewerModesAvailable || state.viewerPaths.isEmpty()) {
            log(tag, WARN) { "openViewer($mode): selection is not all files" }
            return
        }
        val request = openInNewTabsUseCase.createSelectionViewerRequest(state.viewerPaths, mode, workspaceId)
        workspaceRemote.createAndFocus(
            type = request.type,
            arguments = request.arguments,
            sourceWorkspaceId = workspaceId,
            skipContentDedup = request.skipContentDedup,
        )
        clearSelection()
    }

    private suspend fun openEachInTab(items: List<ExplorerItem>) {
        val request = OpenInNewTabsUseCase.Request(
            items = items.mapNotNull { it.toOpenInNewTabsItem() },
            sourceWorkspaceId = workspaceId,
        )
        val analysis = openInNewTabsUseCase.analyze(request)
        if (!analysis.hasItemsToOpen) {
            log(tag, WARN) { "All items skipped (no openable items)" }
            return
        }

        log(tag, INFO) { "openEachInTab(): Opening ${analysis.totalOpenableCount} workspaces" }
        val requests = openInNewTabsUseCase.createRequests(
            analysis = analysis,
            createExplorerArguments = { path -> ExplorerArguments.Default(startPath = path) },
            createViewerArguments = { path ->
                ViewerArguments.Default(filePath = path, listingSourceId = workspaceId)
            },
        )

        // WorkspaceRepo handles the confirmation and the banner
        val result = workspaceRemote.execute(
            WorkspaceAction.CreateBatch(
                requests = requests,
                sourceWorkspaceId = workspaceId,
            )
        )
        when (result) {
            is WorkspaceAction.CreateBatch.Result.Success -> {
                log(tag, INFO) { "Batch creation succeeded: $result" }
            }
            is WorkspaceAction.CreateBatch.Result.Cancelled -> {
                log(tag, INFO) { "Batch creation cancelled by user" }
            }
            is WorkspaceAction.CreateBatch.Result.AwaitingConfirmation -> {
                log(tag, INFO) { "Batch creation awaiting confirmation" }
            }
        }

        clearSelection()
    }

    private fun ExplorerItem.toOpenInNewTabsItem(): OpenInNewTabsUseCase.Item? = when (this) {
        is ExplorerItem.Lookup -> if (lookup.isDirectory) {
            OpenInNewTabsUseCase.Item.Directory(lookup.lookedUp)
        } else {
            val isText = when (this) {
                is ExplorerItem.File -> TextFileDetector.isTextFile(mimeType)
                else -> TextFileDetector.isTextFile(lookup.lookedUp)
            }
            OpenInNewTabsUseCase.Item.File(lookup.lookedUp, isText)
        }
        // USB sticks, SAF locations and network shares are always directories
        is ExplorerItem.Storage -> OpenInNewTabsUseCase.Item.Directory(target.path)
        else -> null
    }
}

/**
 * [selected] in the order the listing shows it, including entries a filter or the hidden-files
 * setting keeps off screen. [sortForDisplay] is the listing's sort and favorite pinning without its
 * filtering, fed in [rawItems] order since some locations (Recent, Device) keep the loader's order.
 * While it is null (the location's sort is not resolved yet) [displayed] gives the order instead.
 * Either way, entries the reference list does not hold go last, in selection order.
 */
internal fun orderSelectionForDisplay(
    selected: Collection<ExplorerItem>,
    rawItems: List<ExplorerItem>?,
    displayed: List<ExplorerItem>?,
    sortForDisplay: ((List<ExplorerItem>) -> List<ExplorerItem>)?,
): List<ExplorerItem> = if (sortForDisplay != null) {
    sortForDisplay(selected.inOrderOf(rawItems))
} else {
    selected.inOrderOf(displayed)
}

private fun Collection<ExplorerItem>.inOrderOf(reference: List<ExplorerItem>?): List<ExplorerItem> {
    val positions = reference.orEmpty().withIndex().associate { (index, item) -> item.id to index }
    val (listed, unlisted) = partition { it.id in positions }
    return listed.sortedBy { positions.getValue(it.id) } + unlisted
}
