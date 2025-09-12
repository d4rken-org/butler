# Explorer Operations Implementation Plan

## Overview

This document outlines the implementation strategy for completing the missing file operations in the Butler Explorer using GatewaySwitch and extension functions. The previous refactoring left placeholder implementations that need to be replaced with actual working operations.

## Key Discoveries

After investigation, we found that:
1. **GatewaySwitch already handles `APath` types** - No star projection issues exist
2. **Extension functions in `APathExtensions.kt` provide the necessary API**
3. **The "star projection" issue was a misunderstanding** - The gateway system is ready to use

## Operations to Implement

### 1. Delete Operation ✅ (Easiest)
Already supported by gateway with extension functions:
- `path.delete(gateway, recursive)`
- `path.deleteWalk(gateway, filter)`

### 2. Create Operations ✅ (Simple)
Already supported:
- `path.createDir(gateway)`
- `path.createDirIfNecessary(gateway)`
- `path.createFile(gateway)`
- `path.createFileIfNecessary(gateway)`

### 3. Rename Operation 🔧 (Medium)
Can be implemented as move within same directory:
- Use move operation with new name in same parent

### 4. Copy Operation ❌ (Complex - Needs Implementation)
Not currently in gateway, needs:
- Progress tracking during copy
- Efficient same-type copying
- Cross-gateway copying support

### 5. Move Operation 🔧 (Built on Copy)
Implemented as copy + delete:
- Copy to destination
- Delete source on success

## Implementation Strategy

### Phase 1: Quick Wins - Basic Operations

#### 1.1 Fix Delete Operation
```kotlin
private suspend fun executeDelete(
    operation: ExplorerOperation.FileOp.Delete,
    operationId: OperationId,
    startTime: Instant,
    emitState: suspend (OperationState) -> Unit,
): OperationMetrics {
    var metrics = OperationMetrics()
    val totalFiles = operation.paths.size
    var processedCount = 0
    
    // Emit hint for UI update
    emitDeleteHint(operation, operationId)
    
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
            
            // Emit progress
            emitProgress(operationId, startTime, processedCount, totalFiles, metrics, emitState)
            
        } catch (e: Exception) {
            if (operation.continueOnError) {
                metrics = metrics.withError()
                log(TAG, WARN) { "Failed to delete $path: ${e.message}" }
            } else {
                throw e
            }
        }
    }
    
    return metrics
}
```

#### 1.2 Fix Create Folder Operation
```kotlin
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
        
        val resolution = handleConflict(operationId, conflict, strategy, emitState)
        when (resolution) {
            is ConflictResolution.Skip -> return OperationMetrics().withSkippedFile()
            is ConflictResolution.Rename -> {
                val newPath = operation.parentPath.child(resolution.newName)
                newPath.createDir(gatewaySwitch)
                return OperationMetrics().withAddedFile(0)
            }
            else -> throw CancellationException("Operation cancelled")
        }
    }
    
    folderPath.createDir(gatewaySwitch)
    return OperationMetrics().withAddedFile(0)
}
```

#### 1.3 Fix Create File Operation
```kotlin
private suspend fun executeCreateFile(
    operation: ExplorerOperation.FileOp.CreateFile,
    operationId: OperationId,
    startTime: Instant,
    emitState: suspend (OperationState) -> Unit,
): OperationMetrics {
    val filePath = operation.parentPath.child(operation.name)
    
    filePath.createFileIfNecessary(gatewaySwitch)
    
    return OperationMetrics().withAddedFile(0)
}
```

### Phase 2: Copy Operation with Progress

#### 2.1 Add Copy Extension Function
```kotlin
// In APathExtensions.kt
suspend fun <T : APath> T.copyTo(
    gateway: APathGateway<T, out APathLookup<T>, out APathLookupExtended<T>>,
    target: T,
    overwrite: Boolean = false,
    onProgress: ((bytescopied: Long, totalBytes: Long) -> Unit)? = null
): T {
    // Check if source exists
    if (!exists(gateway)) {
        throw IOException("Source does not exist: $this")
    }
    
    val sourceLookup = gateway.lookup(this)
    
    // Handle directories
    if (sourceLookup.isDirectory) {
        return copyDirectoryTo(gateway, target, overwrite, onProgress)
    }
    
    // Handle files
    return copyFileTo(gateway, target, overwrite, onProgress)
}

private suspend fun <T : APath> T.copyFileTo(
    gateway: APathGateway<T, out APathLookup<T>, out APathLookupExtended<T>>,
    target: T,
    overwrite: Boolean,
    onProgress: ((Long, Long) -> Unit)?
): T {
    if (!overwrite && target.exists(gateway)) {
        throw IOException("Target already exists: $target")
    }
    
    val sourceLookup = gateway.lookup(this)
    val totalBytes = sourceLookup.size
    
    // Check for same-type optimization
    if (this::class == target::class && gateway is GatewaySwitch) {
        // Try native copy first (faster)
        val nativeCopySuccess = tryNativeCopy(this, target, gateway)
        if (nativeCopySuccess) {
            onProgress?.invoke(totalBytes, totalBytes)
            return target
        }
    }
    
    // Fallback to stream copy
    target.createFileIfNecessary(gateway)
    
    val sourceHandle = gateway.file(this, readWrite = false)
    val targetHandle = gateway.file(target, readWrite = true)
    
    sourceHandle.use { source ->
        targetHandle.use { target ->
            copyWithProgress(source, target, totalBytes, onProgress)
        }
    }
    
    // Copy attributes
    try {
        val modifiedAt = sourceLookup.modifiedAt
        if (modifiedAt != null) {
            target.setModifiedAt(gateway, modifiedAt)
        }
    } catch (e: Exception) {
        log(WARN) { "Failed to copy attributes: ${e.message}" }
    }
    
    return target
}

private suspend fun copyWithProgress(
    source: FileHandle,
    target: FileHandle,
    totalBytes: Long,
    onProgress: ((Long, Long) -> Unit)?
) {
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var bytesCopied = 0L
    
    while (true) {
        val bytesRead = source.read(bytesCopied, buffer, 0, buffer.size)
        if (bytesRead == -1) break
        
        target.write(bytesCopied, buffer, 0, bytesRead)
        bytesCopied += bytesRead
        
        onProgress?.invoke(bytesCopied, totalBytes)
    }
}
```

#### 2.2 Optimized Same-Type Copy (Future Enhancement)
```kotlin
private suspend fun <T : APath> tryNativeCopy(
    source: T,
    target: T,
    gateway: GatewaySwitch
): Boolean {
    // TODO: Implement native copy for same gateway types
    // For LocalPath -> LocalPath: Use Files.copy() or shell cp command
    // For SAFPath -> SAFPath: Use DocumentsContract.copyDocument()
    // This provides significant performance benefits for large files
    
    when (source) {
        is LocalPath -> {
            if (target is LocalPath) {
                // TODO: Use java.nio.file.Files.copy() or ProcessBuilder("cp", ...)
                // return performNativeLocalCopy(source, target)
            }
        }
        is SAFPath -> {
            if (target is SAFPath) {
                // TODO: Use Android's DocumentsContract.copyDocument()
                // return performNativeSAFCopy(source, target)
            }
        }
    }
    
    return false // Not implemented yet
}
```

#### 2.3 Integrate Copy with OperationEngine
```kotlin
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
            totalBytesToCopy += calculateSize(source)
        }
    }
    
    // Emit hint for optimistic UI update
    emitCopyHint(operation, operationId)
    
    for (source in operation.sources) {
        val targetPath = operation.destination.child(source.name)
        
        // Handle conflicts
        if (targetPath.exists(gatewaySwitch)) {
            val resolution = handleConflict(...)
            // Handle resolution...
        }
        
        // Copy with progress tracking
        val sourceSize = gatewaySwitch.lookup(source).size
        source.copyTo(
            gateway = gatewaySwitch,
            target = targetPath,
            overwrite = false,
            onProgress = { bytesCopied, totalBytes ->
                val overallProgress = totalBytesCopied + bytesCopied
                
                emitState(OperationState.OnGoing(
                    operationId = operationId,
                    startTime = startTime,
                    progress = Progress.Data(
                        count = Progress.Count.Counter(processedCount, totalFiles),
                        size = Progress.Size.Bytes(overallProgress, totalBytesToCopy)
                    ),
                    currentItem = source,
                    processedCount = processedCount,
                    totalCount = totalFiles,
                    bytesProcessed = overallProgress,
                    totalBytes = totalBytesToCopy,
                    currentSpeed = calculateSpeed(startTime, overallProgress),
                    estimatedTimeRemaining = estimateTimeRemaining(...)
                ))
            }
        )
        
        processedCount++
        totalBytesCopied += sourceSize
        metrics = metrics.withAddedFile(sourceSize)
    }
    
    return metrics
}
```

### Phase 3: Move and Rename Operations

#### 3.1 Move Operation
```kotlin
private suspend fun executeMove(
    operation: ExplorerOperation.FileOp.Move,
    operationId: OperationId,
    startTime: Instant,
    strategy: ConflictStrategy,
    emitState: suspend (OperationState) -> Unit,
): OperationMetrics {
    // Check for optimized move (same filesystem)
    if (canUseNativeMove(operation.sources, operation.destination)) {
        return executeNativeMove(operation, operationId, startTime, strategy, emitState)
    }
    
    // Fallback to copy + delete
    val copyMetrics = executeCopy(
        ExplorerOperation.FileOp.Copy(
            sources = operation.sources,
            destination = operation.destination,
            options = operation.options.toCopyOptions(),
            operationId = operationId,
        ),
        operationId, startTime, strategy, emitState
    )
    
    // Delete sources after successful copy
    for (source in operation.sources) {
        source.deleteWalk(gatewaySwitch)
    }
    
    return copyMetrics
}

private suspend fun canUseNativeMove(
    sources: Set<APath>,
    destination: APath
): Boolean {
    // Native move possible if:
    // 1. All sources are same type as destination
    // 2. For LocalPath: same filesystem/volume
    // 3. For SAFPath: same document provider
    
    if (sources.all { it::class == destination::class }) {
        when (destination) {
            is LocalPath -> {
                // Check if same filesystem (simplified check)
                return sources.all { source ->
                    source.path.substringBefore("/", "") == 
                    destination.path.substringBefore("/", "")
                }
            }
            is SAFPath -> {
                // Check if same authority
                return sources.all { source ->
                    (source as SAFPath).uri.authority == destination.uri.authority
                }
            }
        }
    }
    return false
}
```

#### 3.2 Rename Operation
```kotlin
private suspend fun executeRename(
    operation: ExplorerOperation.FileOp.Rename,
    operationId: OperationId,
    startTime: Instant,
    emitState: suspend (OperationState) -> Unit,
): OperationMetrics {
    val source = operation.path
    val parent = when (source) {
        is LocalPath -> source.parent() ?: throw IllegalStateException("No parent")
        else -> throw UnsupportedOperationException("Rename only supported for LocalPath")
    }
    
    val target = parent.child(operation.newName)
    
    // Use move for rename (same directory)
    return executeMove(
        ExplorerOperation.FileOp.Move(
            sources = setOf(source),
            destination = parent,
            options = MoveOptions(),
            operationId = operationId,
        ),
        operationId, startTime, ConflictStrategy.ASK, emitState
    )
}
```

## Implementation Phases

### Phase 1: Basic Operations (1 day) ✅ COMPLETED
- [x] Implement delete operation using extension functions
- [x] Implement create folder/file operations
- [x] Update OperationEngine to use real operations
- [x] Test basic operations compilation

### Phase 2: Copy Operation (2-3 days) ✅ COMPLETED
- [x] Add copyTo extension function with progress
- [x] Implement stream-based copy with progress callbacks
- [x] Integrate with OperationEngine
- [x] Add conflict handling for copy

**Note**: Progress callbacks are implemented in the `copyTo` extension but currently not used in OperationEngine due to suspend function limitations in lambdas. Future enhancement could use channels or SharedFlow for async progress updates.

### Phase 3: Move and Rename (1 day)
- [ ] Implement move as copy + delete
- [ ] Add native move detection
- [ ] Implement rename as local move
- [ ] Test move across different path types

### Phase 4: Optimizations (Future)
- [ ] Native copy for LocalPath using Files.copy()
- [ ] Native copy for SAFPath using DocumentsContract
- [ ] Native move operations
- [ ] Batch operation optimizations

## Testing Strategy

### Unit Tests
```kotlin
class OperationEngineTest {
    @Test
    fun `delete operation removes files`() {
        // Test file deletion
    }
    
    @Test
    fun `copy operation tracks progress`() {
        // Test copy with progress updates
    }
    
    @Test
    fun `move uses native when possible`() {
        // Test optimized move detection
    }
}
```

### Integration Tests
- Test operations across different APath types
- Test conflict resolution flows
- Test progress reporting accuracy
- Test cancellation handling

## Performance Considerations

### Copy Performance
- **Buffer Size**: Use 64KB buffers for stream copy
- **Progress Frequency**: Update progress every 100ms or 1MB
- **Native Copy**: 10-100x faster for large files

### Move Performance
- **Same Volume**: Near instant (just metadata update)
- **Cross Volume**: Same as copy + delete
- **Detection**: Check volume/authority to optimize

## Error Handling

### Recoverable Errors
- **File Exists**: Offer rename, overwrite, skip
- **Permission Denied**: Request permission or skip
- **Insufficient Space**: Calculate space, offer cleanup

### Non-Recoverable Errors
- **Source Not Found**: Fail operation
- **I/O Error**: Retry with exponential backoff
- **Gateway Error**: Fail with clear message

## Success Metrics

### Functional
- [ ] All operations work with LocalPath
- [ ] All operations work with SAFPath
- [ ] Progress tracking accurate within 1%
- [ ] Conflict resolution works correctly

### Performance
- [ ] Copy: >50MB/s for local operations
- [ ] Move: <100ms for same-volume moves
- [ ] Delete: >1000 files/second
- [ ] Progress updates: <100ms latency

### User Experience
- [ ] Smooth progress animations
- [ ] Accurate time estimates
- [ ] Clear conflict dialogs
- [ ] Responsive cancellation

## Conclusion

With the understanding that GatewaySwitch and extension functions already provide the necessary infrastructure, implementing the missing operations is straightforward. The main work involves:

1. Replacing placeholder code with extension function calls
2. Adding a copy extension with progress tracking
3. Implementing proper conflict handling
4. Optimizing for same-type operations

This plan provides a clear path to complete the Explorer operations implementation, making the file management functionality fully operational.