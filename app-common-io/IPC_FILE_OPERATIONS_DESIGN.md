# IPC File Operations Design

## Overview

This document describes the architecture for implementing Delete, Copy, and Move operations with elevated privileges (root/ADB) using AIDL IPC, supporting real-time progress streaming and interactive issue resolution.

## Architecture Goals

1. **Unified Framework**: Single callback interface and shared infrastructure for all operations
2. **Type Safety**: Operation-specific event types with compile-time validation
3. **Extensibility**: Easy to add new operations without modifying existing code
4. **Real-time Streaming**: Progress updates stream with minimal latency
5. **Interactive Resolution**: Support for user-driven issue resolution across IPC boundary
6. **Reusability**: Minimize code duplication across operations

## Core Architecture

### High-Level Flow

```
Client Side (Normal Permissions)
  ├─ FileOpsClient.delete/copy/move()
  ├─ Creates FileOperationCallback implementation
  ├─ Calls FileOpsConnection.xxxStream() via AIDL
  └─ Receives RemoteInputStream → converts to Flow<State>

         ↓ (IPC Boundary)

Host Side (Root/ADB Permissions)
  ├─ FileOpsHost.xxxStream() receives request
  ├─ Launches LocalPathXxx.execute() in coroutine
  ├─ Converts progress → OperationEvent → RemoteInputStream
  └─ When issues occur: calls callback.onIssue() (blocks)
```

### Communication Patterns

1. **Progress Streaming** (Host → Client)
    - High frequency, one-way
    - Flow → RemoteInputStream → Flow
    - Parcelable events serialized line-by-line

2. **Issue Resolution** (Host ↔ Client)
    - Low frequency, bidirectional
    - Synchronous callback via AIDL
    - Host blocks until client returns resolution

## Component Design

### 1. Shared AIDL Infrastructure

#### 1.1 FileOperationCallback.aidl

**Purpose**: Generic callback interface for all file operations to resolve issues interactively.

**Location**: `app-common-io/src/main/aidl/eu/darken/butler/common/files/local/ipc/FileOperationCallback.aidl`

```aidl
package eu.darken.butler.common.files.local.ipc;

import eu.darken.butler.common.files.local.ipc.FileOperationIssue;
import eu.darken.butler.common.files.local.ipc.FileOperationIssueResolution;

/**
 * Generic callback for all file operation issue resolution.
 * Used by Delete, Copy, Move, and future operations.
 *
 * This callback is invoked by the host process (root/ADB) when an issue occurs
 * during file operations. The host blocks until the client returns a resolution.
 */
interface FileOperationCallback {
    /**
     * Called when an issue occurs during file operation.
     *
     * @param issue The issue that occurred (permission denied, file exists, etc.)
     * @return Resolution chosen by user (skip, retry, overwrite, cancel, etc.)
     */
    FileOperationIssueResolution onIssue(in FileOperationIssue issue);
}
```

**Key Design Decisions**:

- Single method for all issue types (polymorphic via `FileOperationIssue.issueType`)
- Synchronous blocking call (simpler than async callback management)
- Used by all operations (Delete, Copy, Move, future additions)

---

#### 1.2 FileOperationIssue (Parcelable)

**Purpose**: AIDL-compatible representation of `PathActionIssue` for IPC transport.

**Location**:

- `app-common-io/src/main/aidl/eu/darken/butler/common/files/local/ipc/FileOperationIssue.aidl`
- `app-common-io/src/main/java/eu/darken/butler/common/files/local/ipc/FileOperationIssue.kt`

**AIDL Declaration**:

```aidl
package eu.darken.butler.common.files.local.ipc;

parcelable FileOperationIssue;
```

**Kotlin Implementation**:

```kotlin
package eu.darken.butler.common.files.local.ipc

import android.os.Parcelable
import eu.darken.butler.common.files.local.LocalPathLookup
import kotlinx.parcelize.Parcelize

/**
 * AIDL-compatible representation of PathActionIssue.
 * Supports all issue types across all operations.
 *
 * Design note: Uses flat structure with capability flags rather than sealed class
 * because AIDL doesn't support inheritance/polymorphism well.
 */
@Parcelize
data class FileOperationIssue(
    val issueId: String,
    val issueType: IssueType,

    // Paths involved
    val sourcePath: LocalPathLookup? = null,        // For copy/move operations
    val destinationPath: LocalPathLookup,           // Always present
    val errorMessage: String? = null,

    // Resolution capability flags
    // These tell the client what actions are valid for this specific issue
    val canSkip: Boolean = false,
    val canRetry: Boolean = false,
    val canOverwrite: Boolean = false,
    val canMerge: Boolean = false,
    val canRenameSource: Boolean = false,
    val canRenameDestination: Boolean = false,
    val suggestedName: String? = null,              // For rename conflicts
) : Parcelable {

    enum class IssueType {
        PERMISSION_DENIED,      // AccessDeniedException, SecurityException
        PATH_ALREADY_EXISTS,    // Target file/directory already exists
        INSUFFICIENT_SPACE,     // Not enough disk space
        UNKNOWN_ERROR          // Any other error
    }
}
```

**Key Design Decisions**:

- Flat structure with capability flags (easier for AIDL than inheritance)
- `LocalPathLookup` is already Parcelable (can be transported as-is)
- `issueId` allows tracking issues across IPC boundary if needed
- Exceptions serialized as strings (exceptions don't cross IPC well)

---

#### 1.3 FileOperationIssueResolution (Parcelable)

**Purpose**: AIDL-compatible representation of `PathActionIssue.Resolution`.

**Location**:

- `app-common-io/src/main/aidl/eu/darken/butler/common/files/local/ipc/FileOperationIssueResolution.aidl`
- `app-common-io/src/main/java/eu/darken/butler/common/files/local/ipc/FileOperationIssueResolution.kt`

**AIDL Declaration**:

```aidl
package eu.darken.butler.common.files.local.ipc;

parcelable FileOperationIssueResolution;
```

**Kotlin Implementation**:

```kotlin
package eu.darken.butler.common.files.local.ipc

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * AIDL-compatible representation of PathActionIssue.Resolution.
 * Universal resolution type for all issue types and operations.
 */
@Parcelize
data class FileOperationIssueResolution(
    val resolutionType: ResolutionType,
    val applyToAll: Boolean = false,               // "Apply to all similar issues"
    val newName: String? = null,                   // For RENAME_SOURCE/DESTINATION
    val cancelled: Boolean = false,                // User cancelled operation
    val error: String? = null,                     // Optional error on cancel
) : Parcelable {

    enum class ResolutionType {
        SKIP,                   // Skip this file/directory
        RETRY,                  // Try operation again
        OVERWRITE,              // Overwrite existing file
        MERGE,                  // Merge directory contents
        RENAME_SOURCE,          // Rename source file to avoid conflict
        RENAME_DESTINATION,     // Rename destination file to avoid conflict
        CANCEL                  // Cancel entire operation
    }
}
```

**Key Design Decisions**:

- Single flat resolution type (works for all issue types)
- `applyToAll` flag enables "skip all", "overwrite all", etc.
- `cancelled` flag distinguishes cancel from other resolutions
- Some resolution types only valid for certain issue types (validated in conversion layer)

---

#### 1.4 Updated FileOpsConnection.aidl

**Purpose**: Add streaming methods for Delete, Copy, Move operations.

**Location**: `app-common-io/src/main/aidl/eu/darken/butler/common/files/local/ipc/FileOpsConnection.aidl`

**Additions**:

```aidl
interface FileOpsConnection {
    // ... existing methods (lookup, createDir, etc.) ...

    /**
     * Delete files/directories with streaming progress and interactive issue resolution.
     *
     * @param paths List of paths to delete
     * @param recursive If true, delete directories recursively
     * @param ignoreMissing If true, don't fail if path doesn't exist
     * @param callback Issue resolution callback
     * @return RemoteInputStream streaming DeleteOperationEvent instances
     */
    RemoteInputStream deleteStream(
        in List<LocalPath> paths,
        boolean recursive,
        boolean ignoreMissing,
        in FileOperationCallback callback
    );

    /**
     * Copy files/directories with streaming progress and interactive issue resolution.
     *
     * @param sources List of source paths
     * @param destination Destination directory path
     * @param overwrite If true, overwrite existing files
     * @param preserveAttributes If true, preserve timestamps, permissions, ownership
     * @param followSymlinks If true, follow symbolic links (copy target)
     * @param callback Issue resolution callback
     * @return RemoteInputStream streaming CopyOperationEvent instances
     */
    RemoteInputStream copyStream(
        in List<LocalPath> sources,
        in LocalPath destination,
        boolean overwrite,
        boolean preserveAttributes,
        boolean followSymlinks,
        in FileOperationCallback callback
    );

    /**
     * Move files/directories with streaming progress and interactive issue resolution.
     *
     * @param sources List of source paths
     * @param destination Destination directory path
     * @param preserveAttributes If true, preserve timestamps, permissions, ownership
     * @param overwrite If true, overwrite existing files
     * @param callback Issue resolution callback
     * @return RemoteInputStream streaming MoveOperationEvent instances
     */
    RemoteInputStream moveStream(
        in List<LocalPath> sources,
        in LocalPath destination,
        boolean preserveAttributes,
        boolean overwrite,
        in FileOperationCallback callback
    );
}
```

**Key Design Decisions**:

- All operations return `RemoteInputStream` (consistent with existing `walkStream`, `listFilesStream`)
- Callback passed as parameter (not separate registration call)
- Operation-specific parameters (recursive, followSymlinks, etc.)
- Each operation streams its own event type (type safety)

---

### 2. Operation-Specific Event Types

Each operation has its own sealed class for events streamed via
`RemoteInputStream`. This provides type safety and allows different event structures per operation.

#### 2.1 DeleteOperationEvent

**Purpose**: Events streamed during delete operation.

**Location**:

- `app-common-io/src/main/aidl/eu/darken/butler/common/files/local/ipc/DeleteOperationEvent.aidl`
- `app-common-io/src/main/java/eu/darken/butler/common/files/local/ipc/DeleteOperationEvent.kt`

**AIDL Declaration**:

```aidl
package eu.darken.butler.common.files.local.ipc;

parcelable DeleteOperationEvent;
```

**Kotlin Implementation**:

```kotlin
package eu.darken.butler.common.files.local.ipc

import android.os.Parcelable
import eu.darken.butler.common.files.local.LocalPathLookup
import kotlinx.parcelize.Parcelize

/**
 * Events streamed during delete operation via RemoteInputStream.
 * Each event is serialized individually for real-time streaming.
 */
@Parcelize
sealed class DeleteOperationEvent : Parcelable {

    /**
     * Emitted during scanning phase (counting files/directories).
     * Delete scans tree before deletion to show accurate progress.
     */
    @Parcelize
    data class ScanProgress(
        val currentTarget: LocalPathLookup,
        val itemsFound: Int,
        val bytesFound: Long,
    ) : DeleteOperationEvent()

    /**
     * Emitted during deletion phase (actually deleting files).
     */
    @Parcelize
    data class DeleteProgress(
        val currentTarget: LocalPathLookup,
        val itemsProcessed: Int,
        val totalItems: Int,
        val processedBytes: Long,
        val totalBytes: Long,
        val currentItemStartTime: Long? = null,  // Epoch millis (Instant not Parcelable)
    ) : DeleteOperationEvent()

    /**
     * Final result emitted at end of operation.
     */
    @Parcelize
    data class Result(
        val deleted: List<LocalPathLookup>,
        val skipped: List<LocalPathLookup>,
    ) : DeleteOperationEvent()

    /**
     * Emitted on error or cancellation.
     * After this event, stream closes.
     */
    @Parcelize
    data class Error(
        val message: String,
        val cancelled: Boolean = false,
    ) : DeleteOperationEvent()
}
```

**Key Design Decisions**:

- Two progress event types: `ScanProgress` (counting) and `DeleteProgress` (deleting)
- `currentTarget` provides context for progress UI
- `currentItemStartTime` as epoch millis (kotlin.time.Instant not Parcelable)
- `Error.cancelled` distinguishes user cancellation from errors

---

#### 2.2 CopyOperationEvent

**Purpose**: Events streamed during copy operation.

**Location**:

- `app-common-io/src/main/aidl/eu/darken/butler/common/files/local/ipc/CopyOperationEvent.aidl`
- `app-common-io/src/main/java/eu/darken/butler/common/files/local/ipc/CopyOperationEvent.kt`

**AIDL Declaration**:

```aidl
package eu.darken.butler.common.files.local.ipc;

parcelable CopyOperationEvent;
```

**Kotlin Implementation**:

```kotlin
package eu.darken.butler.common.files.local.ipc

import android.os.Parcelable
import eu.darken.butler.common.files.LocalPath
import kotlinx.parcelize.Parcelize

/**
 * Events streamed during copy operation via RemoteInputStream.
 */
@Parcelize
sealed class CopyOperationEvent : Parcelable {

    /**
     * Emitted during scanning phase (counting files to copy).
     */
    @Parcelize
    data class ScanProgress(
        val currentSource: LocalPath,
        val itemsFound: Int,
        val bytesFound: Long,
    ) : CopyOperationEvent()

    /**
     * Emitted during copy phase (actually copying files).
     * Includes per-file progress for large files.
     */
    @Parcelize
    data class CopyProgress(
        val currentSource: LocalPath,
        val currentDestination: LocalPath,
        val itemsProcessed: Int,
        val totalItems: Int,
        val processedBytes: Long,
        val totalBytes: Long,
        val currentFileSize: Long,          // Size of current file being copied
        val currentFileBytes: Long,         // Bytes copied so far for current file
        val currentFileStartTime: Long? = null,  // Epoch millis
    ) : CopyOperationEvent()

    /**
     * Final result emitted at end of operation.
     */
    @Parcelize
    data class Result(
        val copied: List<Pair<LocalPath, LocalPath>>,  // (source, destination) pairs
        val skipped: List<LocalPath>,
        val copiedBytes: Long,
    ) : CopyOperationEvent()

    /**
     * Emitted on error or cancellation.
     */
    @Parcelize
    data class Error(
        val message: String,
        val cancelled: Boolean = false,
    ) : CopyOperationEvent()
}
```

**Key Design Decisions**:

- `CopyProgress` includes both overall and per-file progress
- Per-file progress important for large files (shows ETA, speed)
- `copied` result includes source→destination pairs for verification
- Both `LocalPath` and `LocalPathLookup` used (Path for identity, Lookup for metadata)

---

#### 2.3 MoveOperationEvent

**Purpose**: Events streamed during move operation.

**Location**:

- `app-common-io/src/main/aidl/eu/darken/butler/common/files/local/ipc/MoveOperationEvent.aidl`
- `app-common-io/src/main/java/eu/darken/butler/common/files/local/ipc/MoveOperationEvent.kt`

**AIDL Declaration**:

```aidl
package eu.darken.butler.common.files.local.ipc;

parcelable MoveOperationEvent;
```

**Kotlin Implementation**:

```kotlin
package eu.darken.butler.common.files.local.ipc

import android.os.Parcelable
import eu.darken.butler.common.files.LocalPath
import kotlinx.parcelize.Parcelize

/**
 * Events streamed during move operation via RemoteInputStream.
 * Structure similar to CopyOperationEvent (move = copy + delete).
 */
@Parcelize
sealed class MoveOperationEvent : Parcelable {

    @Parcelize
    data class ScanProgress(
        val currentSource: LocalPath,
        val itemsFound: Int,
        val bytesFound: Long,
    ) : MoveOperationEvent()

    @Parcelize
    data class MoveProgress(
        val currentSource: LocalPath,
        val currentDestination: LocalPath,
        val itemsProcessed: Int,
        val totalItems: Int,
        val processedBytes: Long,
        val totalBytes: Long,
        val currentFileSize: Long,
        val currentFileBytes: Long,
        val currentFileStartTime: Long? = null,  // Epoch millis
    ) : MoveOperationEvent()

    @Parcelize
    data class Result(
        val movedFiles: List<Pair<LocalPath, LocalPath>>,  // (source, destination) pairs
        val skippedFiles: List<LocalPath>,
        val bytesMoved: Long,
    ) : MoveOperationEvent()

    @Parcelize
    data class Error(
        val message: String,
        val cancelled: Boolean = false,
    ) : MoveOperationEvent()
}
```

**Key Design Decisions**:

- Nearly identical structure to `CopyOperationEvent`
- Separate type for type safety (prevents mixing copy/move events)
- Could potentially share base interface, but simplicity > DRY for now

---

### 3. Generic Streaming Infrastructure

#### 3.1 GenericOperationEventStreaming.kt

**Purpose**: Reusable utilities for streaming Parcelable events through `RemoteInputStream`.

**Location**: `app-common-io/src/main/java/eu/darken/butler/common/files/local/ipc/GenericOperationEventStreaming.kt`

```kotlin
package eu.darken.butler.common.files.local.ipc

import android.os.Parcel
import android.os.Parcelable
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.ipc.RemoteInputStream
import eu.darken.butler.common.ipc.inputStream
import eu.darken.butler.common.ipc.remoteInputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.isActive
import okio.ByteString.Companion.decodeBase64
import okio.ByteString.Companion.toByteString
import java.io.PipedInputStream
import java.io.PipedOutputStream

private val TAG = logTag("FileOps", "IPC", "EventStreaming")

// Buffer size for streaming (balance between IPC overhead and memory usage)
private const val EVENT_BUFFER_SIZE = 512 * 1024  // 512KB

/**
 * Generic extension to stream any Parcelable events through RemoteInputStream.
 *
 * Each event is serialized individually (not batched) for real-time streaming.
 * Events are marshalled to Parcel, encoded as Base64, and written line-by-line.
 *
 * @param scope CoroutineScope for launching streaming job
 * @return RemoteInputStream that client can read from
 */
fun <T : Parcelable> Flow<T>.toRemoteInputStream(
    scope: CoroutineScope
): RemoteInputStream {
    val inputStream = PipedInputStream(EVENT_BUFFER_SIZE)
    val outputStream = PipedOutputStream()
    inputStream.connect(outputStream)

    val buffer = outputStream.writer().buffered(EVENT_BUFFER_SIZE)

    this@toRemoteInputStream
        .onEach { event ->
            // Serialize event to Parcel
            val parcel = Parcel.obtain().apply {
                event.writeToParcel(this, 0)
            }

            // Encode as Base64 and write as line (newline-delimited)
            val encodedEvent = parcel.marshall().toByteString().base64()
            parcel.recycle()

            buffer.write(encodedEvent)
            buffer.write('\n'.code)
            buffer.flush()
        }
        .onCompletion {
            buffer.flush()
            buffer.close()
        }
        .catch { e ->
            log(TAG, ERROR) { "Event streaming failed: ${e.asLog()}" }
            throw e
        }
        .launchIn(scope)

    return inputStream.remoteInputStream()
}

/**
 * Generic extension to read Parcelable events from RemoteInputStream.
 *
 * Reads line-by-line, decodes Base64, unmarshalls Parcel, creates event.
 *
 * @param creator Parcelable.Creator for deserializing events
 * @return Flow of deserialized events
 */
fun <T : Parcelable> RemoteInputStream.toEventFlow(
    creator: Parcelable.Creator<T>
): Flow<T> = flow {
    val buffer = this@toEventFlow.inputStream().reader().buffered(EVENT_BUFFER_SIZE)

    while (currentCoroutineContext().isActive) {
        val line = buffer.readLine() ?: break

        // Decode Base64 and unmarshall Parcel
        val decodedEvent = line.decodeBase64()!!
        val parcel = Parcel.obtain().apply {
            unmarshall(decodedEvent.toByteArray(), 0, decodedEvent.size)
            setDataPosition(0)
        }

        val event = creator.createFromParcel(parcel)
        parcel.recycle()

        emit(event)
    }

    close()
}
```

**Key Design Decisions**:

- Generic: works with any `Parcelable` type
- Line-delimited format: simple, easy to debug
- Base64 encoding: handles binary data safely in text stream
- Individual event streaming: no batching (real-time progress)
- Follows existing pattern from `LocalPathLookupIPCFlow.kt`
- Buffer size tuned for balance (not too small, not too large)

---

### 4. Conversion Utilities

#### 4.1 FileOperationIssueConversion.kt

**Purpose**: Convert between domain types (`PathActionIssue`) and IPC types (`FileOperationIssue`).

**Location**: `app-common-io/src/main/java/eu/darken/butler/common/files/local/ipc/FileOperationIssueConversion.kt`

**Key Functions**:

```kotlin
/**
 * Convert from domain PathActionIssue to IPC FileOperationIssue.
 * Used on host side before calling callback.
 */
fun PathActionIssue.toFileOperationIssue(): FileOperationIssue

/**
 * Convert from IPC FileOperationIssue to domain PathActionIssue.
 * Used on client side when receiving issue from host.
 */
fun FileOperationIssue.toPathActionIssue(): PathActionIssue

/**
 * Convert from domain PathActionIssue.Resolution to IPC FileOperationIssueResolution.
 * Used on client side before returning resolution from callback.
 */
fun PathActionIssue.Resolution.toFileOperationIssueResolution(): FileOperationIssueResolution

/**
 * Convert from IPC FileOperationIssueResolution to domain PathActionIssue.Resolution.
 * Used on host side after receiving resolution from callback.
 *
 * @param issue Original issue (needed for type-specific resolution creation)
 */
fun FileOperationIssueResolution.toPathActionIssueResolution(
    issue: PathActionIssue
): PathActionIssue.Resolution
```

**Implementation Notes**:

- Full implementation provided in earlier design (exhaustive when expressions)
- Validates resolution type matches issue type (throws IllegalArgumentException if mismatch)
- Handles all current `PathActionIssue` subtypes:
    - `InsufficientPermission`
    - `PathAlreadyExists`
    - `InsufficientSpace`
    - `UnknownError`
- Shared across all operations (Delete, Copy, Move)

---

#### 4.2 Operation-Specific Event Conversions

Each operation needs conversions between `XxxAction.State` and `XxxOperationEvent`.

**Location**:

- `app-common-io/src/main/java/eu/darken/butler/common/files/local/ipc/DeleteOperationEventConversion.kt`
- `app-common-io/src/main/java/eu/darken/butler/common/files/local/ipc/CopyOperationEventConversion.kt`
- `app-common-io/src/main/java/eu/darken/butler/common/files/local/ipc/MoveOperationEventConversion.kt`

**Functions per Operation**:

```kotlin
// Host side: Convert from DeleteAction.State.Progress to DeleteOperationEvent
fun DeleteAction.State.Progress<*, LocalPathLookup>.toDeleteOperationEvent(): DeleteOperationEvent

// Host side: Convert from DeleteAction.State.Result to DeleteOperationEvent.Result
fun DeleteAction.State.Result<*, LocalPathLookup>.toDeleteOperationEvent(): DeleteOperationEvent.Result

// Client side: Convert from DeleteOperationEvent.Progress to DeleteAction.State.Progress
fun DeleteOperationEvent.ScanProgress.toDeleteActionProgress(): DeleteAction.State.Progress<LocalPath, LocalPathLookup>
fun DeleteOperationEvent.DeleteProgress.toDeleteActionProgress(): DeleteAction.State.Progress<LocalPath, LocalPathLookup>

// Similar functions for Copy and Move operations
```

**Implementation Notes**:

- Convert between progress data structures
- Handle `kotlin.time.Instant` ↔ epoch millis conversion
- Reconstruct `Progress.Data` objects for UI display
- Each operation has slightly different structure (source/dest pairs, scan vs transfer, etc.)

---

### 5. Host Implementation (FileOpsHost.kt)

**Purpose**: Implement streaming methods in root/ADB process.

**Location**: `app-common-io/src/main/java/eu/darken/butler/common/files/local/ipc/FileOpsHost.kt`

**Pattern for Each Operation**:

```kotlin
override fun deleteStream(
    paths: List<LocalPath>,
    recursive: Boolean,
    ignoreMissing: Boolean,
    callback: FileOperationCallback
): RemoteInputStream = try {
    if (Bugs.isTrace) log(TAG, VERBOSE) { "deleteStream(${paths.size} paths)..." }

    // Create flow for streaming events
    val eventFlow = MutableSharedFlow<DeleteOperationEvent>()

    // Launch delete operation in coroutine
    (appScope + dispatcherProvider.IO).launch {
        try {
            // Execute LocalPathDelete with bridged callbacks
            val result = paths.delete(
                recursive = recursive,
                ignoreMissing = ignoreMissing,
                onProgress = { progress ->
                    // Convert progress to event and emit
                    eventFlow.emit(progress.toDeleteOperationEvent())
                },
                onIssue = { issue ->
                    // Convert issue to AIDL type
                    val aidlIssue = issue.toFileOperationIssue()

                    // Call client callback (BLOCKS here until resolution returned)
                    val aidlResolution = callback.onIssue(aidlIssue)

                    // Convert resolution back to domain type
                    aidlResolution.toPathActionIssueResolution(issue)
                }
            )

            // Emit final result event
            eventFlow.emit(result.toDeleteOperationEvent())

        } catch (e: CancellationException) {
            eventFlow.emit(DeleteOperationEvent.Error("Operation cancelled", cancelled = true))
        } catch (e: Exception) {
            log(TAG, ERROR) { "deleteStream failed: ${e.asLog()}" }
            eventFlow.emit(DeleteOperationEvent.Error(e.message ?: "Unknown error"))
        }
    }

    // Convert Flow to RemoteInputStream using generic utility
    eventFlow.toRemoteInputStream(appScope + dispatcherProvider.IO)

} catch (e: Exception) {
    log(TAG, ERROR) { "deleteStream(paths=$paths) failed\n${e.asLog()}" }
    throw e.wrapToPropagate()
}

// Similar implementations for copyStream() and moveStream()
```

**Key Implementation Details**:

1. Create `MutableSharedFlow` for events (allows multiple emissions from coroutine)
2. Launch operation in coroutine scope (prevents blocking binder thread)
3. Bridge `onProgress` callback: convert and emit events
4. Bridge `onIssue` callback: convert, call AIDL callback (blocks), convert back
5. Emit final result or error event
6. Convert flow to `RemoteInputStream` using generic utility
7. All operations follow same pattern (consistency)

---

### 6. Client Implementation (FileOpsClient.kt)

**Purpose**: Implement client-side methods that consume streaming operations.

**Location**: `app-common-io/src/main/java/eu/darken/butler/common/files/local/ipc/FileOpsClient.kt`

**Pattern for Each Operation**:

```kotlin
suspend fun delete(
    targets: Set<LocalPath>,
    options: DeleteAction.Options<LocalPath> = DeleteAction.Options()
): Flow<DeleteAction.State<LocalPath, LocalPathLookup>> = flow {

    // Create AIDL callback implementation
    val callback = object : FileOperationCallback.Stub() {
        override fun onIssue(issue: FileOperationIssue): FileOperationIssueResolution {
            // Convert from AIDL to domain type
            val pathIssue = issue.toPathActionIssue()

            // Call user's issue handler
            // NOTE: We're on binder thread here, but that's OK for blocking call
            val resolution = runBlocking {
                options.onIssue?.invoke(pathIssue)
                    ?: throw IllegalStateException("No issue handler configured")
            }

            // Convert back to AIDL type
            return resolution.toFileOperationIssueResolution()
        }
    }

    try {
        // Make IPC call to host
        val stream = fileOpsConnection.deleteStream(
            targets.toList(),
            options.recursive,
            options.ignoreMissing,
            callback
        )

        // Convert RemoteInputStream to Flow and map events to states
        stream.toEventFlow(DeleteOperationEvent.CREATOR).collect { event ->
            when (event) {
                is DeleteOperationEvent.ScanProgress -> {
                    emit(event.toDeleteActionProgress())
                }
                is DeleteOperationEvent.DeleteProgress -> {
                    emit(event.toDeleteActionProgress())
                }
                is DeleteOperationEvent.Result -> {
                    emit(
                        DeleteAction.State.Result(
                            deleted = event.deleted.toSet(),
                            skipped = event.skipped.toSet()
                        )
                    )
                }
                is DeleteOperationEvent.Error -> {
                    if (event.cancelled) {
                        throw CancellationException(event.message)
                    } else {
                        throw IOException(event.message)
                    }
                }
            }
        }
    } catch (e: Exception) {
        throw e.refineException()
    }
}

// Similar implementations for copy() and move()
```

**Key Implementation Details**:

1. Create callback stub implementing `FileOperationCallback.Stub()`
2. In callback: convert issue, call user handler, convert resolution
3. Use `runBlocking` in callback (we're on binder thread, blocking is expected)
4. Make IPC call passing callback
5. Convert `RemoteInputStream` to `Flow` using generic utility
6. Map operation events to action states with exhaustive `when`
7. Throw appropriate exceptions for errors/cancellation
8. All operations follow same pattern

---

## File Structure Summary

### New Files to Create

**AIDL Files** (7 files):

1. `app-common-io/src/main/aidl/eu/darken/butler/common/files/local/ipc/FileOperationCallback.aidl`
2. `app-common-io/src/main/aidl/eu/darken/butler/common/files/local/ipc/FileOperationIssue.aidl`
3. `app-common-io/src/main/aidl/eu/darken/butler/common/files/local/ipc/FileOperationIssueResolution.aidl`
4. `app-common-io/src/main/aidl/eu/darken/butler/common/files/local/ipc/DeleteOperationEvent.aidl`
5. `app-common-io/src/main/aidl/eu/darken/butler/common/files/local/ipc/CopyOperationEvent.aidl`
6. `app-common-io/src/main/aidl/eu/darken/butler/common/files/local/ipc/MoveOperationEvent.aidl`

**Kotlin Implementation Files** (10 files):

7. `app-common-io/src/main/java/eu/darken/butler/common/files/local/ipc/FileOperationIssue.kt`
8. `app-common-io/src/main/java/eu/darken/butler/common/files/local/ipc/FileOperationIssueResolution.kt`
9. `app-common-io/src/main/java/eu/darken/butler/common/files/local/ipc/DeleteOperationEvent.kt`
10. `app-common-io/src/main/java/eu/darken/butler/common/files/local/ipc/CopyOperationEvent.kt`
11. `app-common-io/src/main/java/eu/darken/butler/common/files/local/ipc/MoveOperationEvent.kt`
12. `app-common-io/src/main/java/eu/darken/butler/common/files/local/ipc/GenericOperationEventStreaming.kt`
13. `app-common-io/src/main/java/eu/darken/butler/common/files/local/ipc/FileOperationIssueConversion.kt`
14. `app-common-io/src/main/java/eu/darken/butler/common/files/local/ipc/DeleteOperationEventConversion.kt`
15. `app-common-io/src/main/java/eu/darken/butler/common/files/local/ipc/CopyOperationEventConversion.kt`
16. `app-common-io/src/main/java/eu/darken/butler/common/files/local/ipc/MoveOperationEventConversion.kt`

### Modified Files

**AIDL File** (1 file):

1. `app-common-io/src/main/aidl/eu/darken/butler/common/files/local/ipc/FileOpsConnection.aidl`
    - Add: `deleteStream()`, `copyStream()`, `moveStream()` methods

**Kotlin Files** (2 files):

2. `app-common-io/src/main/java/eu/darken/butler/common/files/local/ipc/FileOpsHost.kt`
    - Add: Implementation of 3 new streaming methods

3. `app-common-io/src/main/java/eu/darken/butler/common/files/local/ipc/FileOpsClient.kt`
    - Add: `delete()`, `copy()`, `move()` methods returning `Flow<State>`

---

## Implementation Phases

### Phase 1: Foundation (Shared Infrastructure)

1. Create AIDL files for callback and issues
2. Implement `FileOperationIssue.kt` and `FileOperationIssueResolution.kt`
3. Implement `GenericOperationEventStreaming.kt`
4. Implement `FileOperationIssueConversion.kt`
5. **Test**: Unit tests for conversion functions

### Phase 2: Delete Operation

1. Create `DeleteOperationEvent.aidl` and `.kt`
2. Implement `DeleteOperationEventConversion.kt`
3. Update `FileOpsConnection.aidl` with `deleteStream()`
4. Implement `FileOpsHost.deleteStream()`
5. Implement `FileOpsClient.delete()`
6. **Test**: Integration test with mock IPC, manual test with root

### Phase 3: Copy Operation

1. Create `CopyOperationEvent.aidl` and `.kt`
2. Implement `CopyOperationEventConversion.kt`
3. Update `FileOpsConnection.aidl` with `copyStream()`
4. Implement `FileOpsHost.copyStream()`
5. Implement `FileOpsClient.copy()`
6. **Test**: Integration test, manual test with root

### Phase 4: Move Operation

1. Create `MoveOperationEvent.aidl` and `.kt`
2. Implement `MoveOperationEventConversion.kt`
3. Update `FileOpsConnection.aidl` with `moveStream()`
4. Implement `FileOpsHost.moveStream()`
5. Implement `FileOpsClient.move()`
6. **Test**: Integration test, manual test with root

### Phase 5: Integration

1. Wire up operations in appropriate gateways/repositories
2. Update UI to use new streaming operations
3. End-to-end testing
4. Performance profiling

---

## Testing Strategy

### Unit Tests

- Conversion functions (issue ↔ AIDL issue, resolution ↔ AIDL resolution)
- Event conversions (State ↔ OperationEvent)
- Streaming utilities (mock Parcelable types)

### Integration Tests

- Mock `FileOpsConnection` AIDL interface
- Test full flow: create callback, stream events, resolve issues
- Verify callback invocations, event ordering, final results

### Manual Testing

- Test with actual root/ADB environment
- Large directory deletion (thousands of files)
- Permission errors (trigger issue resolution)
- Cancellation during operation
- Multiple concurrent operations

### Performance Testing

- Measure IPC overhead vs direct operation
- Profile memory usage (streaming vs buffering)
- Test with very large files (GB+ copies)
- Measure callback latency (issue → resolution time)

---

## Risk Analysis

### Risk 1: Binder Transaction Size Limits

**Issue**: AIDL has transaction size limit (~1MB). Large Parcelable objects could fail.

**Mitigation**:

- Stream events individually (not batched)
- `LocalPathLookup` is small (~few hundred bytes)
- Lists in results could be large (thousands of files)
- **Solution**: If result lists exceed limits, stream results too (not just progress)

### Risk 2: Callback Deadlock

**Issue**: Host blocks on `callback.onIssue()`. If client blocked, deadlock occurs.

**Mitigation**:

- Client callback runs on binder thread (not main thread)
- Client must not block on IPC back to host in callback
- Current design doesn't require client → host IPC in callback
- **Monitor**: Add timeout to callback if needed

### Risk 3: Memory Pressure from Streaming

**Issue**: `PipedInputStream`/`PipedOutputStream` buffer in memory. Large operations could OOM.

**Mitigation**:

- Fixed buffer size (512KB)
- Events written/read continuously (not accumulated)
- Backpressure: writer blocks if buffer full (coroutine suspends)
- **Monitor**: Test with very large operations

### Risk 4: Exception Serialization

**Issue**: Exceptions don't serialize well across IPC. Stack traces lost.

**Mitigation**:

- Serialize exception message only (not full exception)
- Log full exception on host side before sending
- Client gets simplified error (acceptable for UI)
- **Enhancement**: Add error codes for better categorization

### Risk 5: Operation Cancellation

**Issue**: Client cancels operation, host may not detect immediately.

**Mitigation**:

- Host checks `currentCoroutineContext().isActive` during operation
- Client closes `RemoteInputStream` on cancel (signals host)
- Host emits `Error(cancelled=true)` event
- **Enhancement**: Add explicit cancel method if needed

### Risk 6: Type Safety at Runtime

**Issue**: AIDL doesn't enforce event type matches operation (e.g., could send CopyEvent for delete).

**Mitigation**:

- Separate AIDL methods per operation (type enforced at compile time)
- Each method returns its specific event type stream
- Client code uses correct CREATOR constant
- **Risk**: Low (would fail immediately in testing)

### Risk 7: Event Loss in Streaming

**Issue**: Default `MutableSharedFlow()` with no buffer could drop events if collector is slow.

**Mitigation**:

- Use buffered SharedFlow: `MutableSharedFlow(replay = 0, extraBufferCapacity = 64)`
- Or use `Channel<Event>(Channel.UNLIMITED)` for guaranteed delivery
- **Status**: Must fix before implementation

---

## Implementation Concerns and Required Fixes

This section documents issues identified during design review that must be addressed during implementation.

### High Priority - Must Fix Before Implementation

#### Concern 1: Pair Not Parcelable

**Issue**: `CopyOperationEvent.Result` and `MoveOperationEvent.Result` use `List<Pair<LocalPath, LocalPath>>`. Kotlin's
`Pair` is **not** Parcelable by default.

**Impact**: Runtime crash when trying to parcel result events.

**Fix**: Create a Parcelable wrapper class:

```kotlin
@Parcelize
data class PathPair(
    val source: LocalPath,
    val destination: LocalPath
) : Parcelable
```

Use `List<PathPair>` instead of `List<Pair<LocalPath, LocalPath>>` in:

- `CopyOperationEvent.Result.copied`
- `MoveOperationEvent.Result.movedFiles`

**Files to Update**:

- `CopyOperationEvent.kt`
- `MoveOperationEvent.kt`
- `CopyOperationEventConversion.kt`
- `MoveOperationEventConversion.kt`

---

#### Concern 2: MutableSharedFlow Buffer Configuration

**Issue**: Using `MutableSharedFlow<Event>()` with default parameters (replay=0, buffer=0) could drop events under load.

**Impact**: Progress updates might be lost, leading to inconsistent UI state.

**Fix**: Configure buffer capacity:

```kotlin
val eventFlow = MutableSharedFlow<DeleteOperationEvent>(
    replay = 0,
    extraBufferCapacity = 64  // Buffer up to 64 events
)
```

**Alternative**: Use `Channel<Event>(Channel.UNLIMITED)` for guaranteed delivery.

**Files to Update**:

- `FileOpsHost.kt` (all three methods: deleteStream, copyStream, moveStream)

---

#### Concern 3: Dead Client Detection

**Issue**: If client process dies while host waits for `callback.onIssue()`, host gets
`DeadObjectException` but doesn't handle it.

**Impact**: Operation hangs or crashes instead of gracefully cancelling.

**Fix**: Wrap callback invocation in try-catch:

```kotlin
onIssue = { issue ->
    try {
        val aidlIssue = issue.toFileOperationIssue()
        val aidlResolution = callback.onIssue(aidlIssue)
        aidlResolution.toPathActionIssueResolution(issue)
    } catch (e: android.os.DeadObjectException) {
        log(TAG, WARN) { "Client died during issue resolution, cancelling operation" }
        throw CancellationException("Client disconnected", e)
    } catch (e: android.os.RemoteException) {
        log(TAG, ERROR) { "IPC error during issue resolution: ${e.asLog()}" }
        throw CancellationException("IPC error", e)
    }
}
```

**Files to Update**:

- `FileOpsHost.kt` (all three methods)

---

#### Concern 4: Result Size Exceeds AIDL Limits

**Issue**:
`Result` events contain complete lists of all deleted/copied/moved files. For operations with thousands of files (e.g., 5000+ files), this could exceed AIDL's ~1MB transaction limit.

**Impact**: Result event fails to transmit, operation appears to hang or fail.

**Solution Options**:

1. **Immediate**: Stream result items incrementally (add `ItemDeleted`/`ItemCopied` events)
2. **Simpler**: Return only counts in Result, let client track items from progress events
3. **Pragmatic**: Keep current design, document limit (~2000-5000 files depending on path length)

**Recommended**: Solution #2 - Return counts only:

```kotlin
// Delete
@Parcelize
data class Result(
    val deletedCount: Int,
    val skippedCount: Int,
    val deletedBytes: Long
) : DeleteOperationEvent()

// Copy
@Parcelize
data class Result(
    val copiedCount: Int,
    val skippedCount: Int,
    val copiedBytes: Long
) : CopyOperationEvent()

// Move
@Parcelize
data class Result(
    val movedCount: Int,
    val skippedCount: Int,
    val bytesMoved: Long
) : MoveOperationEvent()
```

Client can track individual items from Progress events if needed for detailed results.

**Decision Required**: Choose solution before implementing Phase 2.

**Files to Update** (if choosing solution #2):

- `DeleteOperationEvent.kt`
- `CopyOperationEvent.kt`
- `MoveOperationEvent.kt`
- All conversion files
- `DeleteAction.kt` / `CopyAction.kt` / `MoveAction.kt` (update State.Result)

---

### Medium Priority - Should Address During Implementation

#### Concern 5: Callback Timeout

**Issue**: If client's `onIssue` handler hangs or takes very long, host blocks indefinitely.

**Impact**: Operation stalls, user has no way to recover.

**Fix**: Add timeout wrapper around callback invocation:

```kotlin
withTimeout(30_000) {  // 30 second timeout
    callback.onIssue(aidlIssue)
}
```

**Status**: Should add during implementation, especially for production use.

**Files to Update**:

- `FileOpsHost.kt` (all three methods)

---

#### Concern 6: Progress Throttling

**Issue
**: High-frequency progress events (especially during file copy with per-byte progress) could overwhelm IPC and UI.

**Impact**: Performance degradation, unnecessary battery drain.

**Fix**: Already addressed by
`PathOperationProgressTracker.shouldReportProgress()` which throttles to 250ms intervals. Ensure this is used consistently.

**Status**: Verify during implementation that throttling is effective.

---

#### Concern 7: Operation Cancellation Detection

**Issue**: Current design relies on `RemoteInputStream` closure for cancellation detection. This may not be immediate.

**Impact**: Operation continues running on host even after client cancels.

**Potential Fix**: Add explicit cancellation method to AIDL:

```aidl
void cancelOperation(String operationId);
```

**Status**: Monitor during testing. If cancellation is too slow, implement explicit method.

---

### Low Priority - Future Improvements

#### Concern 8: Parcel Size Logging

**Issue**: No visibility into actual Parcel sizes during development.

**Impact**: Could miss size issues until production.

**Fix**: Add debug logging:

```kotlin
if (Bugs.isDebug) {
    val parcel = Parcel.obtain()
    event.writeToParcel(parcel, 0)
    log(TAG, DEBUG) { "Event size: ${parcel.dataSize()} bytes" }
    parcel.recycle()
}
```

**Status**: Add during Phase 1 for monitoring.

---

#### Concern 9: Error Code System

**Issue**: Errors communicated as strings only, no structured error codes.

**Impact**: Client can't distinguish error types programmatically.

**Potential Fix**: Add error codes to `Error` events:

```kotlin
@Parcelize
data class Error(
    val message: String,
    val errorCode: ErrorCode = ErrorCode.UNKNOWN,
    val cancelled: Boolean = false,
) : DeleteOperationEvent()

enum class ErrorCode {
    UNKNOWN,
    PERMISSION_DENIED,
    DISK_FULL,
    PATH_NOT_FOUND,
    OPERATION_CANCELLED
}
```

**Status**: Consider for Phase 5 if needed.

---

#### Concern 10: Operation ID Tracking

**Issue**: No way to track/identify specific operation instances.

**Impact**: Difficult to debug issues with multiple concurrent operations.

**Potential Fix**: Add operation ID to all events:

```kotlin
@Parcelize
sealed class DeleteOperationEvent : Parcelable {
    abstract val operationId: String

    @Parcelize
    data class Progress(
        override val operationId: String,
        // ... other fields
    ) : DeleteOperationEvent()
}
```

**Status**: Consider if concurrent operations become common.

---

#### Concern 11: Progress.Data IPC Optimization

**Issue**: `Progress.Data` contains `CaString` which may not be optimal for IPC. Client reconstructs these anyway.

**Impact**: Minor inefficiency in serialization.

**Observation
**: Current approach is correct - client should build UI strings with proper localization. Host just sends raw data.

**Status**: No change needed, just document this decision.

---

## Summary of Required Fixes

### Before Starting Implementation:

1. ✅ Create `PathPair` Parcelable class
2. ✅ Configure `MutableSharedFlow` with buffer
3. ✅ Add `DeadObjectException` handling
4. ⚠️ Decide on Result event size strategy (counts vs full lists)

### During Phase 1 (Foundation):

5. Add Parcel size debug logging
6. Ensure conversion functions handle all edge cases

### During Phase 2-4 (Operations):

7. Add callback timeout wrapper
8. Verify progress throttling is effective
9. Test cancellation responsiveness

### During Phase 5 (Integration):

10. Monitor for AIDL size issues in real-world usage
11. Consider explicit cancellation API if needed
12. Profile IPC overhead and optimize if necessary

---

## Future Enhancements

### 1. Operation Suspension/Resumption

- Persist operation state
- Resume after process death
- Use WorkManager for background operations

### 2. Batch Operations API

- Delete/copy/move multiple file sets in one call
- Shared progress across all operations
- Transaction-like semantics (all or nothing)

### 3. Bidirectional Streaming

- Host can request additional info from client mid-operation
- Client can update options dynamically
- More complex but more flexible

### 4. Progress Throttling Options

- Let client specify update frequency
- Reduce IPC overhead for fast operations
- Balance responsiveness vs performance

### 5. Detailed Error Reporting

- Structured error types (not just strings)
- Error recovery suggestions
- Per-file success/failure status

### 6. Operation Queuing

- Queue multiple operations on host side
- Client submits operation, gets operation ID
- Poll/stream status for specific operation ID
- Enables true background operations

---

## Conclusion

This design provides a **unified, extensible framework
** for streaming file operations across IPC boundaries with interactive issue resolution. Key strengths:

✅ **Reusability**: Shared callback interface and streaming infrastructure
✅ **Type Safety**: Operation-specific event types, compile-time validation
✅ **Extensibility**: Easy to add new operations without modifying core
✅ **Performance**: Real-time streaming, minimal buffering, efficient serialization
✅ **Maintainability**: Clear patterns, separation of concerns, testable components

The implementation follows Android best practices for AIDL services and matches existing patterns in the Butler codebase (e.g.,
`RemoteInputStream`, `LocalPathLookupIPCFlow`).
