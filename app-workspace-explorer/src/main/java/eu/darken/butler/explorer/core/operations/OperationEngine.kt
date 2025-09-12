package eu.darken.butler.explorer.core.operations

import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.debug.logging.Logging.Priority.DEBUG
import eu.darken.butler.common.debug.logging.Logging.Priority.ERROR
import eu.darken.butler.common.debug.logging.Logging.Priority.WARN
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.FileType
import eu.darken.butler.common.files.GatewaySwitch
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.extensions.delete
import eu.darken.butler.common.files.extensions.deleteWalk
import eu.darken.butler.common.files.extensions.exists
import eu.darken.butler.common.files.extensions.lookup
import eu.darken.butler.common.files.extensions.createFile
import eu.darken.butler.common.files.extensions.createDirIfNecessary
import eu.darken.butler.common.files.extensions.createFileIfNecessary
import eu.darken.butler.common.files.extensions.copyOperation
import eu.darken.butler.common.files.extensions.CopyOperation
import eu.darken.butler.common.files.extensions.isDirectory
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.last
import eu.darken.butler.common.files.extensions.du
import eu.darken.butler.common.progress.Progress
import eu.darken.butler.explorer.core.engine.CopyOptions
import eu.darken.butler.explorer.core.engine.ExplorerOperation
import eu.darken.butler.explorer.core.errors.ConflictResolution
import eu.darken.butler.explorer.core.errors.ExplorerError
import eu.darken.butler.explorer.core.operations.ConflictInfo
import eu.darken.butler.explorer.core.operations.ConflictType
import eu.darken.butler.explorer.core.operations.ConflictStrategy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlin.time.TimeSource
import kotlin.uuid.Uuid

/**
 * Executes file system operations with support for progress tracking,
 * conflict resolution, and cancellation. Operations are executed asynchronously
 * and can be suspended while awaiting user input for conflict resolution.
 */
@Singleton
class OperationEngine @Inject constructor(
    private val gatewaySwitch: GatewaySwitch,
    private val dispatcherProvider: DispatcherProvider,
) {
    private val pendingConflicts = ConcurrentHashMap<OperationId, CompletableDeferred<ConflictResolution>>()
    private val activeOperations = ConcurrentHashMap<OperationId, Job>()
    private val conflictStrategies = ConcurrentHashMap<OperationId, ConflictStrategy>()
    private val mutex = Mutex()
    
    private val _operationHints = MutableSharedFlow<OperationHint>(
        replay = 0,
        extraBufferCapacity = 100
    )
    
    /**
     * Stream of operation hints for optimistic UI updates.
     */
    val operationHints: SharedFlow<OperationHint> = _operationHints.asSharedFlow()
    
    fun execute(
        operation: ExplorerOperation,
        scope: CoroutineScope,
        conflictStrategy: ConflictStrategy = ConflictStrategy.ASK,
    ): Flow<OperationState> = flow {
        val operationId = operation.operationId
        val startTime = TimeSource.Monotonic.markNow().elapsedNow().toKotlinInstant()
        var metrics = OperationMetrics()
        
        log(TAG, DEBUG) { "Starting operation: $operationId - $operation" }
        
        try {
            // Register operation
            activeOperations[operationId] = scope.coroutineContext[Job]!!
            conflictStrategies[operationId] = conflictStrategy
            
            // Emit initial state
            emit(OperationState.OnGoing(
                operationId = operationId,
                startTime = startTime,
                progress = Progress.Data(count = Progress.Count.Indeterminate()),
                canCancel = operation.canCancel,
            ))
            
            // Execute based on operation type
            when (operation) {
                is ExplorerOperation.FileOp.Copy -> {
                    metrics = executeCopy(operation, operationId, startTime, conflictStrategy) { state ->
                        emit(state)
                    }
                }
                is ExplorerOperation.FileOp.Move -> {
                    metrics = executeMove(operation, operationId, startTime, conflictStrategy) { state ->
                        emit(state)
                    }
                }
                is ExplorerOperation.FileOp.Delete -> {
                    metrics = executeDelete(operation, operationId, startTime) { state ->
                        emit(state)
                    }
                }
                is ExplorerOperation.FileOp.CreateFolder -> {
                    metrics = executeCreateFolder(operation, operationId, startTime) { state ->
                        emit(state)
                    }
                }
                is ExplorerOperation.FileOp.CreateFile -> {
                    metrics = executeCreateFile(operation, operationId, startTime) { state ->
                        emit(state)
                    }
                }
                is ExplorerOperation.FileOp.Rename -> {
                    metrics = executeRename(operation, operationId, startTime) { state ->
                        emit(state)
                    }
                }
                else -> {
                    throw UnsupportedOperationException("Operation not yet implemented: $operation")
                }
            }
            
            // Emit success
            emit(OperationState.Completed(
                operationId = operationId,
                startTime = startTime,
                result = OperationResult.Success(
                    metrics = metrics,
                ),
                endTime = TimeSource.Monotonic.markNow().elapsedNow().toKotlinInstant(),
            ))
            
        } catch (e: CancellationException) {
            log(TAG, WARN) { "Operation cancelled: $operationId" }
            emit(OperationState.Completed(
                operationId = operationId,
                startTime = startTime,
                result = OperationResult.Cancelled(
                    metrics = metrics,
                ),
                endTime = TimeSource.Monotonic.markNow().elapsedNow().toKotlinInstant(),
            ))
            throw e
        } catch (e: Exception) {
            log(TAG, ERROR) { "Operation failed: $operationId - ${e.asLog()}" }
            emit(OperationState.Completed(
                operationId = operationId,
                startTime = startTime,
                result = OperationResult.Failure(
                    metrics = metrics,
                    error = when (e) {
                        is ExplorerError -> e
                        else -> ExplorerError.Unknown(e)
                    },
                    exception = e,
                ),
                endTime = TimeSource.Monotonic.markNow().elapsedNow().toKotlinInstant(),
            ))
        } finally {
            // Cleanup
            activeOperations.remove(operationId)
            pendingConflicts.remove(operationId)
            conflictStrategies.remove(operationId)
        }
    }.flowOn(dispatcherProvider.IO)
    
    suspend fun resolveConflict(operationId: OperationId, resolution: ConflictResolution) {
        log(TAG) { "Resolving conflict for operation $operationId: $resolution" }
        pendingConflicts[operationId]?.complete(resolution)
        
        // Update strategy if "apply to all" is set
        if (resolution is ConflictResolution.Skip && resolution.applyToAll ||
            resolution is ConflictResolution.Overwrite && resolution.applyToAll ||
            resolution is ConflictResolution.Merge && resolution.applyToAll) {
            conflictStrategies[operationId] = ConflictStrategy(
                defaultResolution = resolution,
                applyToAll = true,
            )
        }
    }
    
    fun cancelOperation(operationId: OperationId) {
        log(TAG) { "Cancelling operation: $operationId" }
        activeOperations[operationId]?.cancel()
    }
    
    private suspend fun handleConflict(
        operationId: OperationId,
        conflict: ConflictInfo,
        strategy: ConflictStrategy,
        emitState: suspend (OperationState) -> Unit,
    ): ConflictResolution {
        // Check if we have a default resolution
        if (strategy.applyToAll && strategy.defaultResolution != null) {
            log(TAG) { "Using default resolution for conflict: ${strategy.defaultResolution}" }
            return strategy.defaultResolution
        }
        
        // Create deferred for user input
        val deferred = CompletableDeferred<ConflictResolution>()
        val conflictId = Uuid.random().toString()
        
        mutex.withLock {
            pendingConflicts[operationId] = deferred
        }
        
        try {
            // Emit awaiting input state
            emitState(OperationState.AwaitingInput(
                operationId = operationId,
                startTime = TimeSource.Monotonic.markNow().elapsedNow().toKotlinInstant(),
                conflict = conflict,
                conflictId = conflictId,
                timeout = 5.minutes,
            ))
            
            // Wait for resolution with timeout
            return withTimeout(5.minutes) {
                deferred.await()
            }
        } catch (e: Exception) {
            log(TAG, WARN) { "Conflict resolution timed out or failed: ${e.asLog()}" }
            // Default to skip on timeout
            return ConflictResolution.Skip(applyToAll = false)
        } finally {
            mutex.withLock {
                pendingConflicts.remove(operationId)
            }
        }
    }
    
    private suspend fun executeCopy(
        operation: ExplorerOperation.FileOp.Copy,
        operationId: OperationId,
        startTime: Instant,
        strategy: ConflictStrategy,
        emitState: suspend (OperationState) -> Unit,
    ): OperationMetrics {
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
        _operationHints.emit(OperationHint.FilesAdded(
            targetPath = operation.destination,
            files = operation.sources.map { operation.destination.child(it.name) },
            operationId = operationId,
        ))
        
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
                
                val resolution = handleConflict(operationId, conflict, strategy, emitState)
                
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
                                    emitState(OperationState.OnGoing(
                                        operationId = operationId,
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
                                    ))
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
                        emitState(OperationState.OnGoing(
                            operationId = operationId,
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
                        ))
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
    
    
    private suspend fun executeMove(
        operation: ExplorerOperation.FileOp.Move,
        operationId: OperationId,
        startTime: Instant,
        strategy: ConflictStrategy,
        emitState: suspend (OperationState) -> Unit,
    ): OperationMetrics {
        // Emit hint for move operation
        val sourcePath = when (val first = operation.sources.firstOrNull()) {
            is LocalPath -> first.parent() ?: operation.destination
            else -> operation.destination
        }
        val hint = OperationHint.FilesMoved(
            targetPath = operation.destination,
            sourcePath = sourcePath,
            files = operation.sources.toList(),
            operationId = operationId,
        )
        _operationHints.emit(hint.asAdditionHint())
        _operationHints.emit(hint.asRemovalHint())
        
        // Move is copy + delete
        val metrics = executeCopy(
            ExplorerOperation.FileOp.Copy(
                sources = operation.sources,
                destination = operation.destination,
                options = CopyOptions(
                    conflictStrategy = strategy,
                    preserveAttributes = operation.options.preserveAttributes,
                ),
            ),
            operationId,
            startTime,
            strategy,
            emitState,
        )
        
        // Delete sources after successful copy
        for (source in operation.sources) {
            source.deleteWalk(gatewaySwitch)
        }
        
        return metrics
    }
    
    private suspend fun executeDelete(
        operation: ExplorerOperation.FileOp.Delete,
        operationId: OperationId,
        startTime: Instant,
        emitState: suspend (OperationState) -> Unit,
    ): OperationMetrics {
        var metrics = OperationMetrics()
        val totalFiles = operation.paths.size
        var processedCount = 0
        
        // Emit hint that files will be removed
        val parentPath = when (val first = operation.paths.firstOrNull()) {
            is LocalPath -> first.parent()
            else -> null
        }
        if (parentPath != null) {
            _operationHints.emit(OperationHint.FilesRemoved(
                targetPath = parentPath,
                files = operation.paths.toList(),
                operationId = operationId,
            ))
        }
        
        for (path in operation.paths) {
            try {
                // Get size before deletion for metrics
                val size = if (path.exists(gatewaySwitch)) {
                    gatewaySwitch.lookup(path).size
                } else 0L
                
                // Perform deletion
                if (operation.recursive) {
                    path.deleteWalk(gatewaySwitch)
                } else {
                    path.delete(gatewaySwitch)
                }
                
                processedCount++
                metrics = metrics.withRemovedFile(size)
                
                emitState(OperationState.OnGoing(
                    operationId = operationId,
                    startTime = startTime,
                    progress = Progress.Data(count = Progress.Count.Counter(processedCount, totalFiles)),
                    currentItem = path,
                    processedCount = processedCount,
                    totalCount = totalFiles,
                    bytesProcessed = metrics.bytesProcessed,
                ))
            } catch (e: Exception) {
                if (operation.options.skipOnError) {
                    metrics = metrics.withFailedFile()
                    log(TAG, WARN) { "Failed to delete $path: ${e.asLog()}" }
                    continue
                } else {
                    throw e
                }
            }
        }
        
        return metrics
    }
    
    private suspend fun executeCreateFolder(
        operation: ExplorerOperation.FileOp.CreateFolder,
        operationId: OperationId,
        startTime: Instant,
        emitState: suspend (OperationState) -> Unit,
    ): OperationMetrics {
        val folderPath = operation.parentPath.child(operation.name)
        
        // Check for conflicts
        if (folderPath.exists(gatewaySwitch)) {
            val conflict = ConflictInfo(
                type = ConflictType.DIRECTORY_EXISTS,
                sourcePath = folderPath,
                targetPath = folderPath,
            )
            
            val resolution = handleConflict(operationId, conflict, ConflictStrategy.ASK, emitState)
            when (resolution) {
                is ConflictResolution.Skip -> return OperationMetrics().withSkippedFile()
                is ConflictResolution.Rename -> {
                    val newPath = operation.parentPath.child(resolution.newName)
                    gatewaySwitch.createDir(newPath)
                    
                    // Emit hint for the created folder
                    _operationHints.emit(OperationHint.FilesAdded(
                        targetPath = operation.parentPath,
                        files = listOf(newPath),
                        operationId = operationId,
                    ))
                    
                    return OperationMetrics().withAddedDirectory()
                }
                is ConflictResolution.Cancel -> throw CancellationException("Operation cancelled")
                else -> throw CancellationException("Operation cancelled")
            }
        }
        
        gatewaySwitch.createDir(folderPath)
        
        // Emit hint for the created folder
        _operationHints.emit(OperationHint.FilesAdded(
            targetPath = operation.parentPath,
            files = listOf(folderPath),
            operationId = operationId,
        ))
        
        return OperationMetrics().withAddedDirectory()
    }
    
    private suspend fun executeCreateFile(
        operation: ExplorerOperation.FileOp.CreateFile,
        operationId: OperationId,
        startTime: Instant,
        emitState: suspend (OperationState) -> Unit,
    ): OperationMetrics {
        val filePath = operation.parentPath.child(operation.name)
        
        filePath.createFileIfNecessary(gatewaySwitch)
        
        // Emit hint for the created file
        _operationHints.emit(OperationHint.FilesAdded(
            targetPath = operation.parentPath,
            files = listOf(filePath),
            operationId = operationId,
        ))
        
        return OperationMetrics().withAddedFile(0)
    }
    
    private suspend fun executeRename(
        operation: ExplorerOperation.FileOp.Rename,
        operationId: OperationId,
        startTime: Instant,
        emitState: suspend (OperationState) -> Unit,
    ): OperationMetrics {
        // TODO: Implement actual rename with gateway
        // Note: suspend is needed once gateway operations are implemented
        // For now, just log the operation
        log(TAG) { "Rename: ${operation.path} -> ${operation.newName}" }
        
        // Placeholder
        return OperationMetrics().withAddedFile(0L)
    }
    
    private fun kotlin.time.Duration.toKotlinInstant(): Instant {
        return Instant.fromEpochMilliseconds(this.inWholeMilliseconds)
    }
    
    companion object {
        private val TAG = logTag("Explorer", "OperationEngine")
    }
}