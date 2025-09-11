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
import eu.darken.butler.common.progress.Progress
import eu.darken.butler.explorer.core.engine.CopyOptions
import eu.darken.butler.explorer.core.engine.ExplorerOperation
import eu.darken.butler.explorer.core.errors.ConflictResolution
import eu.darken.butler.explorer.core.errors.ExplorerError
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
        
        // Emit hint that files will be added to destination
        _operationHints.emit(OperationHint.FilesAdded(
            targetPath = operation.destination,
            files = operation.sources.map { operation.destination.child(it.name) },
            operationId = operationId,
        ))
        
        for (source in operation.sources) {
            // TODO: Implement actual copy logic with proper gateway handling
            // For now, simulate copy operations
            val targetPath = operation.destination.child(source.name)
            
            // Simulate conflict check (placeholder)
            val hasConflict = false
            
            if (hasConflict) {
                val conflict = ConflictInfo(
                    type = ConflictType.FILE_EXISTS,
                    sourcePath = source,
                    targetPath = targetPath,
                )
                
                val resolution = handleConflict(operationId, conflict, strategy, emitState)
                
                when (resolution) {
                    is ConflictResolution.Skip -> {
                        metrics = metrics.withSkippedFile()
                        continue
                    }
                    is ConflictResolution.Overwrite -> {
                        // Delete target (placeholder)
                        log(TAG) { "Overwrite target: $targetPath" }
                    }
                    is ConflictResolution.Rename -> {
                        val renamedTarget = operation.destination.child(resolution.newName)
                        copyFile(source, renamedTarget)
                    }
                    is ConflictResolution.Cancel -> {
                        throw CancellationException("Operation cancelled by user")
                    }
                    else -> {
                        metrics = metrics.withSkippedFile()
                        continue
                    }
                }
            } else {
                // No conflict, proceed with copy
                copyFile(source, targetPath)
            }
            
            processedCount++
            // Placeholder metrics - assume file with 1KB size
            metrics = metrics.withAddedFile(1024)
            
            // Emit progress
            emitState(OperationState.OnGoing(
                operationId = operationId,
                startTime = startTime,
                progress = Progress.Data(count = Progress.Count.Counter(processedCount, totalFiles)),
                currentItem = source,
                processedCount = processedCount,
                totalCount = totalFiles,
                bytesProcessed = metrics.bytesProcessed,
            ))
        }
        
        return metrics
    }
    
    private suspend fun copyFile(source: APath, target: APath) {
        // TODO: Implement actual copy logic with gateway
        // For now, this is a placeholder that will be replaced with proper implementation
        // The gateway system needs to be refactored to avoid star projections
        log(TAG) { "Copy file: $source -> $target" }
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
            // TODO: Implement actual delete with gateway
            log(TAG) { "Delete source after move: $source" }
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
                // TODO: Implement actual delete with gateway
                log(TAG) { "Delete path: $path (recursive: ${operation.recursive})" }
                processedCount++
                
                // Placeholder - assume file
                metrics = metrics.withAddedFile()
                
                emitState(OperationState.OnGoing(
                    operationId = operationId,
                    startTime = startTime,
                    progress = Progress.Data(count = Progress.Count.Counter(processedCount, totalFiles)),
                    currentItem = path,
                    processedCount = processedCount,
                    totalCount = totalFiles,
                ))
            } catch (e: Exception) {
                if (operation.options.skipOnError) {
                    metrics = metrics.withFailedFile()
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
        // TODO: Implement actual directory creation with gateway
        // Note: suspend is needed once gateway operations are implemented
        val newPath = operation.parentPath.child(operation.name)
        log(TAG) { "Create directory: $newPath" }
        
        return OperationMetrics().withAddedDirectory()
    }
    
    private suspend fun executeCreateFile(
        operation: ExplorerOperation.FileOp.CreateFile,
        operationId: OperationId,
        startTime: Instant,
        emitState: suspend (OperationState) -> Unit,
    ): OperationMetrics {
        // TODO: Implement actual file creation with gateway
        // Note: suspend is needed once gateway operations are implemented
        val newPath = operation.parentPath.child(operation.name)
        log(TAG) { "Create file: $newPath" }
        
        return OperationMetrics().withAddedFile()
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