package eu.darken.butler.explorer.core.operations

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.Restore
import androidx.compose.ui.graphics.vector.ImageVector
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import eu.darken.butler.common.ca.CaString
import eu.darken.butler.common.ca.caString
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.GatewaySwitch
import eu.darken.butler.common.files.LookupOptions
import eu.darken.butler.common.getQuantityString2
import eu.darken.butler.common.trash.TrashManager
import eu.darken.butler.common.trash.TrashRepo
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.filesystem.FileSystemHinter
import eu.darken.butler.workspace.core.operations.Operation
import eu.darken.butler.workspace.core.operations.OperationPathPlan
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow

class RestoreOperation @AssistedInject constructor(
    @Assisted workspaceId: Workspace.Id,
    @Assisted private val command: ExplorerCommand.Restore,
    private val trashRepo: TrashRepo,
    private val trashManager: TrashManager,
    private val gatewaySwitch: GatewaySwitch,
    private val fileSystemHinter: FileSystemHinter,
) : ExplorerOperation() {

    private val tag = logTag("Explorer", "Workspace", workspaceId.shortTag, "Operation", "Restore")

    override val metadata: Operation.Metadata = object : Operation.Metadata {
        override val origin = Operation.Metadata.Origin.Explorer(workspaceId)
        override val icon: ImageVector = Icons.TwoTone.Restore
        override val title = eu.darken.butler.workspace.R.string.workspace_operation_restore_title.toCaString()
        override val description = caString { cx ->
            val count = command.restoredPaths.size
            cx.getQuantityString2(
                eu.darken.butler.workspace.R.plurals.workspace_operation_restore_description,
                count,
                count,
            )
        }
        override val kind = Operation.Metadata.Kind.RESTORE
        override val pathPlan = OperationPathPlan(
            targets = command.restoredPaths,
        )
    }

    data class Report(
        val restoredPaths: Set<APath<*>>,
        val conflictCount: Int,
        val failedCount: Int,
    ) : ExplorerOperation.Report {
        override val summary: CaString = caString { cx ->
            cx.getQuantityString2(
                eu.darken.butler.workspace.R.plurals.workspace_operation_restore_summary,
                restoredPaths.size,
                restoredPaths.size,
            )
        }
        override val affectedPaths: Collection<Operation.Report.PathChange> = restoredPaths.map {
            Operation.Report.PathChange(path = it, change = Operation.Report.PathChange.Change.ADDED)
        }
        override val partialErrorCount: Int = conflictCount + failedCount
    }

    override fun perform(operationContext: Operation.Context): Flow<State> = channelFlow {
        log(tag) { "perform(): $command" }

        send(State.Active(startedAt = operationContext.startedAt))

        val restoredPaths = mutableSetOf<APath<*>>()
        var conflicts = 0
        var failed = 0

        if (command.rootItemIds.isNotEmpty()) {
            try {
                val repoItems = command.rootItemIds.mapNotNull { trashRepo.getById(it) }
                failed += command.rootItemIds.size - repoItems.size
                if (repoItems.isNotEmpty()) {
                    val result = trashManager.restore(repoItems)
                    restoredPaths += result.restored
                    conflicts += result.conflicts.size
                    failed += result.failed.size
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                log(tag, ERROR) { "Root restore failed: ${e.asLog()}" }
                failed += command.rootItemIds.size
            }
        }

        command.nestedItems.groupBy { it.parentId }.forEach { (parentId, nestedTargets) ->
            val parentRepoItem = try {
                trashRepo.getById(parentId)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                log(tag, ERROR) { "Failed to resolve parent trash item $parentId: ${e.asLog()}" }
                null
            }
            if (parentRepoItem == null) {
                log(tag, ERROR) { "Parent trash item not found: $parentId" }
                failed += nestedTargets.size
                return@forEach
            }
            nestedTargets.forEach { target ->
                try {
                    val result = trashManager.restoreNested(parentRepoItem, target.relativePath)
                    restoredPaths += result.restored
                    conflicts += result.conflicts.size
                    failed += result.failed.size
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    log(tag, ERROR) { "Nested restore failed for ${target.relativePath}: ${e.asLog()}" }
                    failed++
                }
            }
        }

        if (restoredPaths.isNotEmpty()) {
            val lookups = restoredPaths.mapNotNull { path ->
                try {
                    gatewaySwitch.lookup(path, LookupOptions())
                } catch (e: Exception) {
                    log(tag, WARN) { "Restored, but lookup failed for $path: ${e.asLog()}" }
                    null
                }
            }
            fileSystemHinter.trackPathsAdded(operationContext.id, lookups)
        }

        log(tag, INFO) { "Restore done: ${restoredPaths.size} restored, $conflicts conflicts, $failed failed" }
        send(
            State.Completed(
                startedAt = operationContext.startedAt,
                report = Report(
                    restoredPaths = restoredPaths,
                    conflictCount = conflicts,
                    failedCount = failed,
                ),
                error = when {
                    restoredPaths.isEmpty() && conflicts + failed > 0 ->
                        IllegalStateException("No items could be restored")
                    else -> null
                },
            )
        )
    }

    @AssistedFactory
    interface Factory {
        fun create(
            workspaceId: Workspace.Id,
            command: ExplorerCommand.Restore,
        ): RestoreOperation
    }
}
