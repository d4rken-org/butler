package eu.darken.butler.explorer.ui.explorer

import android.content.Context
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.trash.TrashManager
import eu.darken.butler.common.trash.TrashRepo
import eu.darken.butler.explorer.R
import eu.darken.butler.explorer.core.ExplorerNavigation
import eu.darken.butler.explorer.core.ExplorerWorkspace
import eu.darken.butler.explorer.core.engine.ExplorerItem
import eu.darken.butler.explorer.core.operations.ExplorerCommand
import eu.darken.butler.explorer.core.operations.RestoreOperation
import kotlinx.coroutines.CoroutineScope

/**
 * One-shot trash actions: restore/permanent-delete for root and nested trash items,
 * and emptying the bin. Failure surfaces through [onError]; success refreshes the listing.
 */
class ExplorerTrashController(
    private val context: Context,
    private val trashManager: TrashManager,
    private val trashRepo: TrashRepo,
    private val workspace: suspend () -> ExplorerWorkspace,
    private val clearSelection: () -> Unit,
    private val onError: (Throwable) -> Unit,
    private val doLaunch: (suspend CoroutineScope.() -> Unit) -> Unit,
    private val tag: String,
) {

    fun restoreRoot(items: Collection<ExplorerItem.Trash.Root>) = doLaunch {
        log(tag) { "restoreTrashItems(): ${items.size} items" }
        if (items.isEmpty()) return@doLaunch
        restoreViaOperation(
            command = ExplorerCommand.Restore(
                rootItemIds = items.map { it.itemId }.toSet(),
                restoredPaths = items.map { it.originalLookup.lookedUp },
            ),
        )
    }

    /**
     * Restores run as managed operations: visible in the operations bar, logged to history, and
     * emitting filesystem events so listings, search results, and folder previews update.
     */
    private suspend fun restoreViaOperation(command: ExplorerCommand.Restore) {
        try {
            val completed = workspace().execute(command)
            val report = completed.report as? RestoreOperation.Report

            if (completed.error != null && (report == null || report.restoredPaths.isEmpty())) {
                // Crashed or fully failed operation — nothing to refresh
                log(tag, ERROR) { "Restore failed: ${completed.error?.asLog()}" }
                onError(completed.error ?: Exception(context.getString(R.string.explorer_trash_error_restore_failed)))
                return
            }

            when {
                report == null || report.restoredPaths.isNotEmpty() -> {
                    workspace().navigate(ExplorerNavigation.Refresh)
                    clearSelection()
                    // Partial problems still deserve a notification after the refresh
                    when {
                        report == null -> Unit
                        report.conflictCount > 0 ->
                            onError(Exception(context.getString(R.string.explorer_trash_nested_restore_conflict)))
                        report.failedCount > 0 ->
                            onError(Exception(context.getString(R.string.explorer_trash_error_restore_failed)))
                    }
                }
                report.conflictCount > 0 ->
                    onError(Exception(context.getString(R.string.explorer_trash_nested_restore_conflict)))
                else ->
                    onError(Exception(context.getString(R.string.explorer_trash_error_restore_failed)))
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            // User cancellation is not an error
            throw e
        } catch (e: Exception) {
            log(tag, ERROR) { "Error restoring trash items: ${e.asLog()}" }
            onError(e)
        }
    }

    fun deleteRootPermanently(items: Collection<ExplorerItem.Trash.Root>) = doLaunch {
        log(tag) { "deleteTrashItemsPermanently(): ${items.size} items" }
        if (items.isEmpty()) return@doLaunch
        try {
            val repoItems = items.mapNotNull { trashRepo.getById(it.itemId) }
            if (repoItems.isEmpty()) {
                onError(Exception(context.getString(R.string.explorer_trash_error_items_not_found)))
                clearSelection()
                return@doLaunch
            }
            val deletedCount = trashManager.deletePermanently(repoItems)
            if (deletedCount > 0) {
                log(tag, INFO) { "Successfully deleted $deletedCount items permanently" }
                workspace().navigate(ExplorerNavigation.Refresh)
                clearSelection()
            } else {
                log(tag, ERROR) { "Failed to delete items permanently" }
                onError(Exception(context.getString(R.string.explorer_trash_error_delete_failed)))
            }
        } catch (e: Exception) {
            log(tag, ERROR) { "Error deleting trash items permanently: ${e.asLog()}" }
            onError(e)
        }
    }

    fun restoreNested(items: Collection<ExplorerItem.Trash.Nested>) = doLaunch {
        log(tag) { "restoreNestedItems(): ${items.size} items" }
        if (items.isEmpty()) return@doLaunch
        restoreViaOperation(
            command = ExplorerCommand.Restore(
                nestedItems = items.map {
                    ExplorerCommand.Restore.NestedTarget(
                        parentId = it.parentRef.itemId,
                        relativePath = it.relativePath,
                    )
                },
                restoredPaths = items.map { it.originalRestoredPath },
            ),
        )
    }

    fun deleteNestedPermanently(items: Collection<ExplorerItem.Trash.Nested>) = doLaunch {
        log(tag) { "deleteNestedItemsPermanently(): ${items.size} items" }
        if (items.isEmpty()) return@doLaunch
        try {
            var totalDeleted = 0
            // Group items by parent to reduce duplicate repo lookups
            val itemsByParent = items.groupBy { it.parentRef.itemId }

            for ((parentId, parentItems) in itemsByParent) {
                val parentRepoItem = trashRepo.getById(parentId)
                if (parentRepoItem == null) {
                    log(tag, ERROR) { "Parent trash item not found: $parentId" }
                    onError(Exception(context.getString(R.string.explorer_trash_nested_parent_missing)))
                    continue
                }

                for (item in parentItems) {
                    val deletedCount =
                        trashManager.deleteNestedPermanently(parentRepoItem, item.relativePath)
                    if (deletedCount > 0) {
                        totalDeleted += deletedCount
                        log(tag, INFO) { "Successfully deleted nested item permanently" }
                    } else {
                        log(tag, ERROR) { "Failed to delete nested item permanently" }
                        onError(
                            Exception(
                                context.getString(
                                    R.string.explorer_trash_nested_error_delete_failed,
                                    item.displayName.get(context)
                                )
                            )
                        )
                    }
                }
            }
            if (totalDeleted > 0) {
                workspace().navigate(ExplorerNavigation.Refresh)
                clearSelection()
            }
        } catch (e: Exception) {
            log(tag, ERROR) { "Error deleting nested trash items: ${e.asLog()}" }
            onError(e)
        }
    }

    suspend fun emptyTrash() {
        try {
            val deletedCount = trashManager.emptyTrash()
            log(tag, INFO) { "Emptied trash: $deletedCount items deleted" }
            workspace().navigate(ExplorerNavigation.Refresh)
        } catch (e: Exception) {
            log(tag, ERROR) { "Failed to empty trash: ${e.asLog()}" }
            onError(e)
        }
    }
}
