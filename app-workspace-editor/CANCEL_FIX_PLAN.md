# Fix: Cancel Not Working for Editor File Loading

## Problem

When opening a large file in the editor, pressing "Cancel" does not stop the loading. Chunks continue to load despite cancellation.

## Previous Attempts That Didn't Work

1. **Two-pronged cancellation** (VM job + engine.cancelInitialization) - chunks kept loading
2. **Additional `ensureActive()` checkpoints** after I/O operations - chunks kept loading

## Root Cause

The issue is a **job hierarchy problem**. When `DynamicStateFlow.updateBlocking` runs, it executes within a `channelFlow`'s internal scope. The `initializationJob` captured by `EditorEngine.initialize()` is a child of this channelFlow's job.

When `cancelInitialization()` calls `initializationJob.cancel()`:
- The specific job reference gets cancelled
- But `ensureActive()` calls in `ChunkedTextBuffer` and `ChunkManager` check their own `coroutineContext` which may reference different jobs in the hierarchy
- The cancellation doesn't propagate correctly through the nested job hierarchy

## Solution

Use an **explicit cancellation flag** (`AtomicBoolean`) that bypasses the coroutine job hierarchy entirely.

### 1. EditorEngine.kt - Add cancellation flag

```kotlin
// Add field (around line 31)
private val cancellationFlag = AtomicBoolean(false)

// In initialize() - reset flag at start (around line 153)
suspend fun initialize(): Result<Unit> = stateMutex.withLock {
    cancellationFlag.set(false)
    initializationJob = currentCoroutineContext()[Job]
    // ... existing code ...

    // Pass flag to text buffer (around line 175)
    val bufferInitResult = resources.textBuffer.initialize(cancellationFlag)
    // ...
}

// In cancelInitialization() - set the flag (around line 229)
fun cancelInitialization() {
    log(tag, INFO) { "Cancelling initialization" }
    cancellationFlag.set(true)  // ADD: Set flag
    initializationJob?.cancel()
    initializationJob = null
    _state.value = EditorState.Empty
}
```

### 2. ChunkedTextBuffer.kt - Accept and check flag

```kotlin
// Update initialize() signature (line 56)
suspend fun initialize(cancellationFlag: AtomicBoolean? = null): Result<Unit> = bufferMutex.withLock {
    // ... existing code ...
    buildChunkMetadata(cancellationFlag)
    // ...
}

// Update buildChunkMetadata() signature and add checks (line 835)
private suspend fun buildChunkMetadata(cancellationFlag: AtomicBoolean? = null) {
    // ... existing code ...

    for ((index, chunkId) in chunkIds.withIndex()) {
        // Check explicit flag first (bypasses job hierarchy)
        if (cancellationFlag?.get() == true) {
            throw CancellationException("File loading cancelled")
        }
        coroutineContext.ensureActive()  // Keep as backup

        // ... chunk loading code ...

        if (boundary.lineCount <= 0) {
            val chunk = chunkManager.getChunk(chunkId)
                ?: chunkManager.loadChunk(chunkId).getOrThrow()

            // Check after I/O
            if (cancellationFlag?.get() == true) {
                throw CancellationException("File loading cancelled")
            }
            // ...
        }
    }
}
```

### 3. ChunkManager.kt - Accept and check flag in getChunksInRange (optional)

```kotlin
// Update getChunksInRange() signature (line 256)
suspend fun getChunksInRange(
    startOffset: Long,
    endOffset: Long,
    cancellationFlag: AtomicBoolean? = null
): List<TextChunk> {
    // ... existing code ...

    for (chunkId in relevantChunkIds) {
        if (cancellationFlag?.get() == true) {
            throw CancellationException("Chunk loading cancelled")
        }
        coroutineContext.ensureActive()

        val loadResult = loadChunk(chunkId)
        // ...
    }
}
```

## Files to Modify

1. **EditorEngine.kt** - Add `cancellationFlag` field, reset in `initialize()`, set in `cancelInitialization()`, pass to text buffer
2. **ChunkedTextBuffer.kt** - Add `cancellationFlag` parameter to `initialize()` and `buildChunkMetadata()`, check flag in loop
3. **ChunkManager.kt** - Optionally add `cancellationFlag` parameter to `getChunksInRange()` and check in loop

## Implementation Steps

1. Add `cancellationFlag: AtomicBoolean` field to EditorEngine
2. Update `initialize()` to reset flag and pass to text buffer
3. Update `cancelInitialization()` to set flag to `true`
4. Update `ChunkedTextBuffer.initialize()` to accept flag parameter
5. Update `buildChunkMetadata()` to accept and check flag before/after I/O
6. (Optional) Update `ChunkManager.getChunksInRange()` similarly
7. Build and test with large file

## Why This Works

The `AtomicBoolean` flag is:
- Shared directly between the cancellation call site and the loading loops
- Independent of coroutine job hierarchy
- Checked at every iteration and after every I/O operation
- Thread-safe via atomic operations

This bypasses the complex job hierarchy created by `DynamicStateFlow`'s `channelFlow` and ensures cancellation is detected immediately.

## Required Import

```kotlin
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
```
