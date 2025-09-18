package eu.darken.butler.explorer.core.operations.handlers

import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.GatewaySwitch
import eu.darken.butler.common.files.extensions.CopyOperation
import eu.darken.butler.common.files.extensions.copyOperation
import eu.darken.butler.common.files.extensions.createDirIfNecessary
import eu.darken.butler.common.files.extensions.deleteWalk
import eu.darken.butler.common.files.extensions.du
import eu.darken.butler.common.files.extensions.exists
import eu.darken.butler.common.files.extensions.isDirectory
import eu.darken.butler.common.files.extensions.lookup
import eu.darken.butler.common.files.extensions.walk
import eu.darken.butler.common.files.metadata.FileType
import eu.darken.butler.common.files.operations.Issue
import eu.darken.butler.common.progress.Progress
import eu.darken.butler.explorer.core.engine.ExplorerOperation
import eu.darken.butler.explorer.core.operations.IssueHandler
import eu.darken.butler.explorer.core.operations.OperationContext
import eu.darken.butler.explorer.core.operations.OperationNotifier
import eu.darken.butler.explorer.core.operations.OperationState
import eu.darken.butler.workspace.core.Workspace
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.flow.onEach

class CopyOperationHandler @AssistedInject constructor(
    @Assisted workspaceId: Workspace.Id,
    @Assisted private val issueHandler: IssueHandler,
    gatewaySwitch: GatewaySwitch,
    dispatcherProvider: DispatcherProvider,
) : BaseOperationHandler<ExplorerOperation.FileOp.Copy>(
    workspaceId,
    gatewaySwitch,
    dispatcherProvider,
) {
    private val tag = logTag("Explorer", "Workspace", workspaceId.shortTag, "Operation", "Copy")

    override suspend fun executeInContext(
        context: OperationContext,
        operation: ExplorerOperation.FileOp.Copy,
    ): Unit = with(context) {
        log(tag) { "execute(): $operation" }

        var progress = OperationState.OnGoing(
            operationId = operationId,
            startedAt = startedAt,
        )

        var processedCount = 0
        operation.sources.size

        for (source in operation.sources) {
            var targetPath = operation.destination.child(source.name)
            val sourceLookup = source.lookup(gatewaySwitch)
            val targetLookup = targetPath.lookup(gatewaySwitch)

            // Check for conflicts
            if (targetPath.exists(gatewaySwitch)) {
                val issue = Issue.PathAlreadyExists(
                    source = sourceLookup,
                    destination = targetLookup,
                    canMerge = targetLookup.isDirectory && sourceLookup.isDirectory,
                )

                val resolution = (issueHandler.handleIssue(
                    context = context,
                    issue = issue,
                ) ?: Issue.PathAlreadyExists.Resolution.Cancel) as Issue.PathAlreadyExists.Resolution

                when (resolution) {
                    is Issue.PathAlreadyExists.Resolution.Skip -> {
                        processedCount++
                        continue
                    }
                    is Issue.PathAlreadyExists.Resolution.Overwrite -> {
                        // Delete target before copy
                        targetPath.deleteWalk(gatewaySwitch)
                    }
                    is Issue.PathAlreadyExists.Resolution.RenameSource -> {
                        targetPath = operation.destination.child(resolution.newName)
                    }
                    is Issue.PathAlreadyExists.Resolution.RenameDestination -> {
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
                    is Issue.PathAlreadyExists.Resolution.Merge -> {
                        if (!sourceLookup.isDirectory && targetLookup.isDirectory) {
                            throw IllegalArgumentException("Can't merge files, only folders.")
                        }
                    }
                    is Issue.PathAlreadyExists.Resolution.Cancel -> {
                        throw CancellationException("Operation cancelled by user")
                    }
                }
            }

            val copyResult = if (sourceLookup.isDirectory) {
                copyDirectory(source, targetPath, context)
            } else {
                source.copyOperation(
                    gateway = gatewaySwitch,
                    target = targetPath,
                    overwrite = targetPath.exists(gatewaySwitch)
                )
            }
                .onEach { copyOp ->
                    when (copyOp.state) {
                        CopyOperation.State.COPYING -> {
                            emit(
                                progress.copy(
                                    actionProgress = Progress.Data(
                                        count = Progress.Count.Size(
                                            current = copyOp.bytesCopied,
                                            max = copyOp.bytesTotal,
                                        )
                                    ),

                                    )
                            )
                        }
                        else -> {} // Ignore other states
                    }
                }
                .last() // Wait for completion and get final result

            if (copyResult.state != CopyOperation.State.COMPLETED) {
                val issue = Issue.UnknownError(
                    exception = copyResult.error!!,
                    source = sourceLookup,
                    destination = targetPath.lookup(gatewaySwitch),
                    canSkip = true,
                    canRetry = true,
                )

                val resolution = (issueHandler.handleIssue(
                    context = context,
                    issue = issue,
                ) ?: Issue.UnknownError.Resolution.Cancel) as Issue.UnknownError.Resolution

                when (resolution) {
                    is Issue.UnknownError.Resolution.Skip -> {
                        processedCount++
                        continue
                    }
                    is Issue.UnknownError.Resolution.Retry -> {
                        // TODO
                    }
                    is Issue.UnknownError.Resolution.Cancel -> {
                        throw CancellationException("Operation cancelled by user")
                    }
                }
            }

            processedCount++
//            totalBytesCopied += copyResult.totalBytes

            OperationNotifier.Hint.FilesAdded(
                operationId = operation.operationId,
                affectedFolder = operation.destination,
                files = listOf(targetPath),
            ).run { emit(this) }
        }

    }

    private suspend fun copyDirectory(
        source: APath,
        target: APath,
        context: OperationContext
    ): Flow<CopyOperation> = flow {
        log(tag) { "copyDirectory(): $source -> $target" }

        // Create target directory first
        target.createDirIfNecessary(gatewaySwitch)

        var totalBytesCopied = 0L
        var totalFilesProcessed = 0
        val totalSize = source.du(gatewaySwitch)

        // Walk through source directory
        source.walk(gatewaySwitch).collect { itemLookup ->
            val itemPath = itemLookup.lookedUp

            // Skip the root directory itself
            if (itemPath == source) return@collect

            // Calculate relative path and target path
            val relativePath = getRelativePath(itemPath, source)
            var targetPath = target.child(*relativePath.toTypedArray())

            if (itemLookup.fileType == FileType.FILE) {
                // Handle file conflicts before copying
                if (targetPath.exists(gatewaySwitch)) {
                    val issue = Issue.PathAlreadyExists(
                        source = itemLookup,
                        destination = targetPath.lookup(gatewaySwitch),
                        canMerge = false
                    )

                    val resolution = (issueHandler.handleIssue(
                        context = context,
                        issue = issue,
                    ) ?: Issue.PathAlreadyExists.Resolution.Cancel) as Issue.PathAlreadyExists.Resolution

                    when (resolution) {
                        is Issue.PathAlreadyExists.Resolution.Skip -> {
                            return@collect // Skip this file
                        }
                        is Issue.PathAlreadyExists.Resolution.Overwrite -> {
                            targetPath.deleteWalk(gatewaySwitch)
                        }
                        is Issue.PathAlreadyExists.Resolution.RenameSource -> {
                            val newRelativePath = relativePath.dropLast(1) + resolution.newName
                            targetPath = target.child(*newRelativePath.toTypedArray())
                        }
                        is Issue.PathAlreadyExists.Resolution.RenameDestination -> {
                            // Rename the existing file to make room for the incoming file
                            val existingPath = targetPath
                            val newExistingParent = target.child(*relativePath.dropLast(1).toTypedArray())
                            val newExistingPath = newExistingParent.child(resolution.newName)
                            existingPath.copyOperation(
                                gateway = gatewaySwitch,
                                target = newExistingPath,
                                overwrite = false
                            ).last()
                            existingPath.deleteWalk(gatewaySwitch)
                        }
                        is Issue.PathAlreadyExists.Resolution.Merge -> {
                            // For files, merging is not possible - this should not happen
                            throw IllegalArgumentException("Can't merge files, only folders.")
                        }
                        is Issue.PathAlreadyExists.Resolution.Cancel -> {
                            throw CancellationException("Operation cancelled by user")
                        }
                    }
                }

                // Copy the individual file
                itemPath.copyOperation(
                    gateway = gatewaySwitch,
                    target = targetPath,
                    overwrite = true
                ).collect { progress ->
                    when (progress.state) {
                        CopyOperation.State.COPYING -> {
                            emit(
                                CopyOperation(
                                    state = CopyOperation.State.COPYING,
                                    from = source,
                                    to = target,
                                    bytesCopied = totalBytesCopied + progress.bytesCopied,
                                    bytesTotal = totalSize,
                                )
                            )
                        }
                        CopyOperation.State.COMPLETED -> {
                            totalBytesCopied += progress.bytesTotal
                            totalFilesProcessed++
                        }
                        CopyOperation.State.FAILED -> throw progress.error!!
                        else -> {} // Skip other states
                    }
                }
            } else if (itemLookup.fileType == FileType.DIRECTORY) {
                // Check for directory conflicts (e.g. if target exists as a file)
                if (targetPath.exists(gatewaySwitch)) {
                    val targetLookup = targetPath.lookup(gatewaySwitch)
                    if (targetLookup.fileType == FileType.FILE) {
                        val issue = Issue.PathAlreadyExists(
                            source = itemLookup,
                            destination = targetLookup,
                            canMerge = false
                        )

                        val resolution = (issueHandler.handleIssue(
                            context = context,
                            issue = issue,
                        ) ?: Issue.PathAlreadyExists.Resolution.Cancel) as Issue.PathAlreadyExists.Resolution

                        when (resolution) {
                            is Issue.PathAlreadyExists.Resolution.Skip -> {
                                return@collect // Skip this directory
                            }
                            is Issue.PathAlreadyExists.Resolution.Overwrite -> {
                                targetPath.deleteWalk(gatewaySwitch)
                            }
                            is Issue.PathAlreadyExists.Resolution.RenameSource -> {
                                val newRelativePath = relativePath.dropLast(1) + resolution.newName
                                targetPath = target.child(*newRelativePath.toTypedArray())
                            }
                            is Issue.PathAlreadyExists.Resolution.RenameDestination -> {
                                // Rename the existing file to make room for the incoming directory
                                val existingPath = targetPath
                                val newExistingParent = target.child(*relativePath.dropLast(1).toTypedArray())
                                val newExistingPath = newExistingParent.child(resolution.newName)
                                existingPath.copyOperation(
                                    gateway = gatewaySwitch,
                                    target = newExistingPath,
                                    overwrite = false
                                ).last()
                                existingPath.deleteWalk(gatewaySwitch)
                            }
                            is Issue.PathAlreadyExists.Resolution.Merge -> {
                                throw IllegalArgumentException("Can't merge directory with file.")
                            }
                            is Issue.PathAlreadyExists.Resolution.Cancel -> {
                                throw CancellationException("Operation cancelled by user")
                            }
                        }
                    }
                }

                // Create directory
                targetPath.createDirIfNecessary(gatewaySwitch)
            }
        }

        // Emit completion
        emit(
            CopyOperation(
                state = CopyOperation.State.COMPLETED,
                from = source,
                to = target,
                bytesCopied = totalBytesCopied,
                bytesTotal = totalSize,
            )
        )
    }

    private fun getRelativePath(itemPath: APath, basePath: APath): List<String> {
        val itemSegments = itemPath.segments
        val baseSegments = basePath.segments

        return if (itemSegments.size > baseSegments.size) {
            itemSegments.drop(baseSegments.size)
        } else {
            emptyList()
        }
    }

    @AssistedFactory
    interface Factory {
        fun create(
            workspaceId: Workspace.Id,
            issueHandler: IssueHandler,
        ): CopyOperationHandler
    }
}