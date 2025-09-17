package eu.darken.butler.explorer.core.operations.handlers

import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.GatewaySwitch
import eu.darken.butler.common.files.extensions.CopyOperation
import eu.darken.butler.common.files.extensions.copyOperation
import eu.darken.butler.common.files.extensions.deleteWalk
import eu.darken.butler.common.files.extensions.du
import eu.darken.butler.common.files.extensions.exists
import eu.darken.butler.common.files.extensions.isDirectory
import eu.darken.butler.common.files.extensions.lookup
import eu.darken.butler.common.progress.Progress
import eu.darken.butler.explorer.core.engine.ExplorerOperation
import eu.darken.butler.explorer.core.operations.OperationContext
import eu.darken.butler.explorer.core.operations.OperationMetrics
import eu.darken.butler.explorer.core.operations.OperationNotifier
import eu.darken.butler.explorer.core.operations.OperationState
import eu.darken.butler.explorer.core.operations.conflicts.Conflict
import eu.darken.butler.explorer.core.operations.conflicts.ConflictHandler
import eu.darken.butler.workspace.core.Workspace
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.flow.onEach

class CopyOperationHandler @AssistedInject constructor(
    @Assisted workspaceId: Workspace.Id,
    @Assisted private val conflictHandler: ConflictHandler,
    @Assisted operationNotifier: OperationNotifier,
    gatewaySwitch: GatewaySwitch,
    dispatcherProvider: DispatcherProvider,
) : BaseOperationHandler<ExplorerOperation.FileOp.Copy>(
    workspaceId,
    gatewaySwitch,
    dispatcherProvider,
    operationNotifier
) {
    private val tag = logTag("Explorer", "Workspace", workspaceId.shortTag, "Operation", "Copy")

    override suspend fun executeInContext(
        context: OperationContext,
        operation: ExplorerOperation.FileOp.Copy,
    ): OperationMetrics {
        with(context) {
        log(tag) { "execute(): $operation" }
        var metrics = OperationMetrics()
        val totalFiles = operation.sources.size
        var processedCount = 0
        var totalBytesToCopy = 0L
        var totalBytesCopied = 0L

        // Calculate total size for progress
        for (source in operation.sources) {
            if (source.exists(gatewaySwitch)) {
                totalBytesToCopy += if (gatewaySwitch.lookup(source).isDirectory) {
                    source.du(gatewaySwitch)
                } else {
                    gatewaySwitch.lookup(source).size
                }
            }
        }

        // Emit hint that files will be added to destination
        operationNotifier.publish(
            OperationNotifier.Hint.FilesAdded(
                targetPath = operation.destination,
                files = operation.sources.map { operation.destination.child(it.name) },
                operationId = operation.operationId,
            )
        )

        for (source in operation.sources) {
            var targetPath = operation.destination.child(source.name)
            val sourceLookup = source.lookup(gatewaySwitch)
            val targetLookup = targetPath.lookup(gatewaySwitch)

            // Check for conflicts
            if (targetPath.exists(gatewaySwitch)) {
                val conflict = Conflict.PathAlreadyExists(
                    source = sourceLookup,
                    destination = targetLookup,
                    canMerge = targetLookup.isDirectory && sourceLookup.isDirectory,
                )

                val resolution = (conflictHandler.handleConflict(
                    context = context,
                    conflict = conflict,
                ) ?: Conflict.PathAlreadyExists.Resolution.Cancel) as Conflict.PathAlreadyExists.Resolution

                when (resolution) {
                    is Conflict.PathAlreadyExists.Resolution.Skip -> {
                        metrics = metrics.withSkippedFile()
                        processedCount++
                        continue
                    }
                    is Conflict.PathAlreadyExists.Resolution.Overwrite -> {
                        // Delete target before copy
                        targetPath.deleteWalk(gatewaySwitch)
                    }
                    is Conflict.PathAlreadyExists.Resolution.Rename -> {
                        targetPath = operation.destination.child(resolution.newName)
                    }
                    is Conflict.PathAlreadyExists.Resolution.RenameExisting -> {
                        // Rename the existing file to make room for the incoming file
                        val existingPath = targetPath
                        val newExistingPath = operation.destination.child(resolution.newName)
                        existingPath.copyOperation(
                            gateway = gatewaySwitch,
                            target = newExistingPath,
                            overwrite = false
                        ).last()
                        existingPath.deleteWalk(gatewaySwitch)
                    }
                    is Conflict.PathAlreadyExists.Resolution.Merge -> {
                        if (!sourceLookup.isDirectory && targetLookup.isDirectory) {
                            throw IllegalArgumentException("Can't merge files, only folders.")
                        }
                    }
                    is Conflict.PathAlreadyExists.Resolution.Cancel -> {
                        throw CancellationException("Operation cancelled by user")
                    }
                }
            }

            val copyResult = source
                .copyOperation(
                    gateway = gatewaySwitch,
                    target = targetPath,
                    overwrite = targetPath.exists(gatewaySwitch) // true if we deleted for overwrite
                )
                .onEach { copyOp ->
                    when (copyOp.state) {
                        CopyOperation.State.COPYING -> {
                            emit(
                                OperationState.OnGoing(
                                    operationId = operation.operationId,
                                    startTime = startTime,
                                    progress = Progress.Data(
                                        count = Progress.Count.Size(
                                            totalBytesCopied + copyOp.bytesCopied,
                                            totalBytesToCopy
                                        )
                                    ),
                                    currentItem = copyOp.currentPath ?: source,
                                    processedCount = processedCount,
                                    totalCount = totalFiles,
                                    bytesProcessed = totalBytesCopied + copyOp.bytesCopied,
                                    totalBytes = totalBytesToCopy,
                                )
                            )
                        }
                        else -> {} // Ignore other states
                    }
                }
                .last() // Wait for completion and get final result

            if (copyResult.state != CopyOperation.State.COMPLETED) {
                val conflict = Conflict.UnknownError(
                    exception = copyResult.error!!,
                    source = sourceLookup,
                    destination = targetPath.lookup(gatewaySwitch),
                    canSkip = true,
                    canRetry = true,
                )

                val resolution = (conflictHandler.handleConflict(
                    context = context,
                    conflict = conflict,
                ) ?: Conflict.UnknownError.Resolution.Cancel) as Conflict.UnknownError.Resolution

                when (resolution) {
                    is Conflict.UnknownError.Resolution.Skip -> {
                        metrics = metrics.withSkippedFile()
                        processedCount++
                        continue
                    }
                    is Conflict.UnknownError.Resolution.Retry -> {
                        // TODO
                    }
                    is Conflict.UnknownError.Resolution.Cancel -> {
                        throw CancellationException("Operation cancelled by user")
                    }
                }
            }

            processedCount++
            totalBytesCopied += copyResult.totalBytes
            metrics = metrics.withAddedFile(copyResult.totalBytes)
        }

            return metrics
        }
    }

    @AssistedFactory
    interface Factory {
        fun create(
            workspaceId: Workspace.Id,
            operationNotifier: OperationNotifier,
            conflictHandler: ConflictHandler,
        ): CopyOperationHandler
    }
}