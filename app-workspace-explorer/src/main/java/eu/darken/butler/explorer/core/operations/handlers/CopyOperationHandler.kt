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
import eu.darken.butler.common.progress.Progress
import eu.darken.butler.explorer.core.engine.ExplorerOperation
import eu.darken.butler.explorer.core.operations.ConflictInfo
import eu.darken.butler.explorer.core.operations.ConflictType
import eu.darken.butler.explorer.core.operations.OperationMetrics
import eu.darken.butler.explorer.core.operations.OperationNotifier
import eu.darken.butler.explorer.core.operations.OperationState
import eu.darken.butler.explorer.core.operations.conflicts.ConflictHandler
import eu.darken.butler.explorer.core.operations.conflicts.ConflictResolution
import eu.darken.butler.workspace.core.Workspace
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.flow.onEach
import java.io.IOException
import kotlin.time.Instant

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

    override suspend fun execute(
        operation: ExplorerOperation.FileOp.Copy,
        startTime: Instant,
        emitState: suspend (OperationState) -> Unit,
    ): OperationMetrics {
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
            val targetPath = operation.destination.child(source.name)

            // Check for conflicts
            if (targetPath.exists(gatewaySwitch)) {
                val conflict = ConflictInfo(
                    type = if (gatewaySwitch.lookup(targetPath).isDirectory) {
                        ConflictType.DIRECTORY_EXISTS
                    } else {
                        ConflictType.FILE_EXISTS
                    },
                    sourcePath = source,
                    targetPath = targetPath,
                )

                val resolution = conflictHandler.handleConflict(
                    operationId = operation.operationId,
                    conflict = conflict,
                    strategy = operation.options.conflictStrategy,
                    emitState = emitState
                )

                when (resolution) {
                    is ConflictResolution.Skip -> {
                        metrics = metrics.withSkippedFile()
                        processedCount++
                        continue
                    }
                    is ConflictResolution.Overwrite -> {
                        // Delete target before copy
                        targetPath.deleteWalk(gatewaySwitch)
                    }
                    is ConflictResolution.Rename -> {
                        val renamedTarget = operation.destination.child(resolution.newName)

                        // Copy with progress tracking
                        val copyResult = source.copyOperation(
                            gateway = gatewaySwitch,
                            target = renamedTarget,
                            overwrite = false
                        )
                            .onEach { copyOp ->
                                when (copyOp.state) {
                                    CopyOperation.State.COPYING -> {
                                        emitState(
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
                                    CopyOperation.State.FAILED -> {
                                        throw copyOp.error ?: IOException("Copy failed")
                                    }
                                    else -> {} // Ignore other states
                                }
                            }
                            .last() // Wait for completion and get final result

                        // Check the result
                        if (copyResult.state != CopyOperation.State.COMPLETED) {
                            throw IOException("Copy operation did not complete successfully")
                        }

                        val sourceSize = copyResult.totalBytes
                        totalBytesCopied += sourceSize
                        metrics = metrics.withAddedFile(sourceSize)
                        processedCount++
                        continue
                    }
                    is ConflictResolution.Cancel -> {
                        throw CancellationException("Operation cancelled by user")
                    }
                    else -> {
                        metrics = metrics.withSkippedFile()
                        processedCount++
                        continue
                    }
                }
            }

            // No conflict or overwrite resolved, proceed with copy
            // Copy with progress tracking
            val copyResult = source.copyOperation(
                gateway = gatewaySwitch,
                target = targetPath,
                overwrite = targetPath.exists(gatewaySwitch) // true if we deleted for overwrite
            )
                .onEach { copyOp ->
                    when (copyOp.state) {
                        CopyOperation.State.COPYING -> {
                            emitState(
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
                        CopyOperation.State.FAILED -> {
                            throw copyOp.error ?: IOException("Copy failed")
                        }
                        else -> {} // Ignore other states
                    }
                }
                .last() // Wait for completion and get final result

            // Check the result
            if (copyResult.state != CopyOperation.State.COMPLETED) {
                throw IOException("Copy operation did not complete successfully")
            }

            processedCount++
            totalBytesCopied += copyResult.totalBytes
            metrics = metrics.withAddedFile(copyResult.totalBytes)
        }

        return metrics
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