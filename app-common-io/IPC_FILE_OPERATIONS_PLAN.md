# IPC File Operations - Next Steps Plan

**Created:** 2025-10-17
**Status:** Ready to implement Phase 3 (Copy operation)

---

## Context

We're continuing implementation of the IPC file operations framework. Phase 1 (Foundation) and Phase 2 (Delete) are complete. We reviewed the critical issues identified in the status document and determined:

- ✅ **Issue 1 (Buffer)**: NO CHANGE NEEDED - `flow { }` + `PipedOutputStream` already provides natural backpressure blocking
- ⚠️ **Issue 2 (Dead Client)**: NEEDS FIX - Should throw `IOException`, not `CancellationException`
- ❌ **Issue 3 (Timeout)**: DO NOT ADD - Bad UX, let Android/UI layer handle timeouts

---

## Step 1: Fix Dead Client Handling in Delete (5 minutes)

**File:** `app-common-io/src/main/java/eu/darken/butler/common/files/local/ipc/FileOpsHost.kt`

**Location:** Lines 248-252 (inside `deleteStream()` method)

**Current code:**
```kotlin
suspend fun handleIssue(issue: PathActionIssue): PathActionIssue.Resolution {
    val ipcIssue = issue.toFileOperationIssue()
    val ipcResolution = callback!!.onIssue(ipcIssue)
    return ipcResolution.toPathActionIssueResolution(issue)
}
```

**Change to:**
```kotlin
suspend fun handleIssue(issue: PathActionIssue): PathActionIssue.Resolution {
    val ipcIssue = issue.toFileOperationIssue()
    try {
        val ipcResolution = callback!!.onIssue(ipcIssue)
        return ipcResolution.toPathActionIssueResolution(issue)
    } catch (e: android.os.DeadObjectException) {
        log(TAG, ERROR) { "Client process died during issue resolution" }
        throw IOException("Client process died", e)
    } catch (e: android.os.RemoteException) {
        log(TAG, ERROR) { "IPC error during issue resolution: ${e.asLog()}" }
        throw IOException("IPC communication failed", e)
    }
}
```

**Rationale:**
- Client death is an error condition, not graceful cancellation
- `IOException` will be caught by `.catch { }` block (line 274) and emit proper Error event
- No timeout needed - let Android handle stuck IPC, let UI handle user inactivity

---

## Step 2: Create CopyOperationEvent.kt (20 minutes)

**New file:** `app-common-io/src/main/java/eu/darken/butler/common/files/local/ipc/CopyOperationEvent.kt`

**Structure:** Follow `DeleteOperationEvent.kt` pattern exactly

```kotlin
package eu.darken.butler.common.files.local.ipc

import android.os.Parcel
import android.os.Parcelable
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.local.LocalPathLookup
import kotlinx.parcelize.Parcelize

sealed interface CopyOperationEvent : Parcelable {

    companion object {
        @JvmField
        val CREATOR: Parcelable.Creator<CopyOperationEvent> = object : Parcelable.Creator<CopyOperationEvent> {
            override fun createFromParcel(parcel: Parcel): CopyOperationEvent {
                val className = parcel.readString()!!
                parcel.setDataPosition(0)
                return parcel.readParcelable(
                    CopyOperationEvent::class.java.classLoader,
                    Class.forName(className)
                ) as CopyOperationEvent
            }

            override fun newArray(size: Int): Array<CopyOperationEvent?> = arrayOfNulls(size)
        }
    }

    @Parcelize
    data class ScanProgress(
        val scannedCount: Long,
        val scannedBytes: Long,
        val currentPath: LocalPathLookup,
    ) : CopyOperationEvent

    @Parcelize
    data class CopyProgress(
        val copiedCount: Long,
        val totalCount: Long,
        val copiedBytes: Long,
        val totalBytes: Long,
        val currentSource: LocalPathLookup,
        val currentDestination: LocalPath,
        val currentFileSize: Long,
        val currentFileBytes: Long,
    ) : CopyOperationEvent

    @Parcelize
    data class Result(
        val copiedItems: List<PathPair>,
        val skippedItems: List<LocalPathLookup>,
        val errorCount: Int,
        val copiedBytes: Long,
    ) : CopyOperationEvent

    @Parcelize
    data class Error(
        val error: String,
        val cancelled: Boolean,
    ) : CopyOperationEvent
}
```

**Key differences from Delete:**
- Has source + destination paths (not just targets)
- Includes per-file progress (currentFileSize/currentFileBytes) for large files
- Uses `PathPair` for result mappings (already created in Phase 1)
- `currentDestination` is `LocalPath` (may not exist yet during copy)

**Also create:** `app-common-io/src/main/aidl/eu/darken/butler/common/files/local/ipc/CopyOperationEvent.aidl`

```aidl
package eu.darken.butler.common.files.local.ipc;

parcelable CopyOperationEvent;
```

---

## Step 3: Create CopyOperationEventConversion.kt (30 minutes)

**New file:** `app-common-io/src/main/java/eu/darken/butler/common/files/local/ipc/CopyOperationEventConversion.kt`

**Functions needed:**

```kotlin
package eu.darken.butler.common.files.local.ipc

import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.actions.CopyAction
import eu.darken.butler.common.files.local.LocalPathLookup

// Host side: Convert domain State to IPC event
fun CopyAction.State<LocalPath, LocalPathLookup>.toCopyOperationEvent(): CopyOperationEvent

// Client side: Convert IPC event to domain State
fun CopyOperationEvent.toCopyActionState(): CopyAction.State<LocalPath, LocalPathLookup>
```

**Pattern:** Follow `DeleteOperationEventConversion.kt` with exhaustive `when` expressions

**Challenge areas:**
- Copy has TWO progress phases (scan + copy), map appropriately
- Handle `currentFileSize`/`currentFileBytes` for per-file progress
- Convert `PathPair` ↔ `Pair<LocalPath, LocalPath>` in results

---

## Step 4: Update FileOpsConnection.aidl (5 minutes)

**File:** `app-common-io/src/main/aidl/eu/darken/butler/common/files/local/ipc/FileOpsConnection.aidl`

**Add method:**

```aidl
RemoteInputStream copyStream(
    in List<LocalPath> sources,
    in LocalPath destination,
    boolean overwrite,
    boolean preserveAttributes,
    boolean followSymlinks,
    in FileOperationCallback callback
);
```

**Placement:** After `deleteStream()`, before closing brace

---

## Step 5: Implement FileOpsHost.copyStream() (45 minutes)

**File:** `app-common-io/src/main/java/eu/darken/butler/common/files/local/ipc/FileOpsHost.kt`

**Add method after `deleteStream()`:**

```kotlin
override fun copyStream(
    sources: List<LocalPath>,
    destination: LocalPath,
    overwrite: Boolean,
    preserveAttributes: Boolean,
    followSymlinks: Boolean,
    callback: FileOperationCallback?
): RemoteInputStream = try {
    log(TAG, VERBOSE) { "copyStream(): ${sources.size} sources → $destination" }

    val eventFlow = flow<CopyOperationEvent> {
        // Issue handler with dead client protection
        suspend fun handleIssue(issue: PathActionIssue): PathActionIssue.Resolution {
            val ipcIssue = issue.toFileOperationIssue()
            try {
                val ipcResolution = callback!!.onIssue(ipcIssue)
                return ipcResolution.toPathActionIssueResolution(issue)
            } catch (e: android.os.DeadObjectException) {
                log(TAG, ERROR) { "Client process died during issue resolution" }
                throw IOException("Client process died", e)
            } catch (e: android.os.RemoteException) {
                log(TAG, ERROR) { "IPC error during issue resolution: ${e.asLog()}" }
                throw IOException("IPC communication failed", e)
            }
        }

        val onIssue: (suspend (PathActionIssue) -> PathActionIssue.Resolution)? =
            if (callback != null) ::handleIssue else null

        // Execute copy with progress callback
        val result = sources.copy(
            fileSystemOps = fileSystemOps,
            destination = destination,
            overwrite = overwrite,
            preserveAttributes = preserveAttributes,
            followSymlinks = followSymlinks,
            onIssue = onIssue,
            onProgress = { progress ->
                val event = progress.toCopyOperationEvent()
                emit(event)
            }
        )

        // Emit final result
        val resultEvent = result.toCopyOperationEvent()
        emit(resultEvent)
    }
        .catch { e ->
            log(TAG, ERROR) { "copyStream() operation failed: ${e.asLog()}" }
            emit(
                CopyOperationEvent.Error(
                    error = e.message ?: "Unknown error",
                    cancelled = false
                )
            )
        }
        .onCompletion { error ->
            if (error != null) {
                log(TAG, ERROR) { "copyStream() completion with error: ${error.asLog()}" }
            } else {
                log(TAG, VERBOSE) { "copyStream() completed successfully" }
            }
        }

    eventFlow.toRemoteInputStream(appScope + dispatcherProvider.IO)

} catch (e: Exception) {
    log(TAG, ERROR) { "copyStream(sources=${sources.size}) setup failed\n${e.asLog()}" }
    throw e.wrapToPropagate()
}
```

**Note:** Will need to verify that `Collection<LocalPath>.copy()` extension exists with these parameters. May need to check existing copy implementation.

---

## Step 6: Implement FileOpsClient.copy() (30 minutes)

**File:** `app-common-io/src/main/java/eu/darken/butler/common/files/local/ipc/FileOpsClient.kt`

**Add method after `delete()`:**

```kotlin
fun copy(
    sources: Set<LocalPath>,
    destination: LocalPath,
    options: CopyAction.Options<LocalPath>,
): Flow<CopyAction.State<LocalPath, LocalPathLookup>> = try {
    log(TAG, VERBOSE) { "copy(): ${sources.size} sources → $destination" }

    // Create AIDL callback wrapper
    val callback: FileOperationCallback? = options.onIssue?.let { issueHandler ->
        object : FileOperationCallback.Stub() {
            override fun onIssue(issue: FileOperationIssue): FileOperationIssueResolution {
                val domainIssue = issue.toPathActionIssue()
                val resolution = kotlinx.coroutines.runBlocking {
                    issueHandler(domainIssue)
                }
                return resolution.toFileOperationIssueResolution()
            }
        }
    }

    // Call host's copyStream()
    val remoteInputStream = fileOpsConnection.copyStream(
        sources.toList(),
        destination,
        options.overwrite,
        options.preserveAttributes,
        options.followSymlinks,
        callback
    )

    // Convert RemoteInputStream to Flow
    remoteInputStream.toEventFlow(CopyOperationEvent.CREATOR)
        .map { event ->
            event.toCopyActionState()
        }
} catch (e: Exception) {
    throw e.refineException()
}
```

**Pattern:** Nearly identical to `delete()` method, just different parameters

---

## Step 7: Build & Verify (10 minutes)

```bash
# Build the module
./gradlew :app-common-io:compileDebugKotlin --no-daemon

# Check for errors
# Verify AIDL stub generation
# Review build output
```

**Expected output:** BUILD SUCCESSFUL

**If build fails:** Check for missing imports, verify AIDL files are in correct location, check parameter types

---

## Completion Criteria

- [ ] Step 1: Dead client handling added to `deleteStream()`
- [ ] Step 2: `CopyOperationEvent.kt` and `.aidl` created
- [ ] Step 3: `CopyOperationEventConversion.kt` created with bidirectional conversions
- [ ] Step 4: `FileOpsConnection.aidl` updated with `copyStream()` method
- [ ] Step 5: `FileOpsHost.copyStream()` implemented
- [ ] Step 6: `FileOpsClient.copy()` implemented
- [ ] Step 7: Build successful, no compilation errors
- [ ] Update `IPC_FILE_OPERATIONS_STATUS.md` to mark Phase 3 complete

---

## Next After This

**Phase 4:** Move operation (follow same pattern as Copy)

**Phase 5:** Integration testing with actual root/ADB environment

---

## Notes & Decisions

### Why no MutableSharedFlow buffer?
The existing `flow { }` + `PipedOutputStream` provides natural backpressure. When the pipe buffer fills, `buffer.write()` blocks, suspending the flow collector until the consumer reads. This is correct behavior - we want operations to wait for progress consumption, not drop events.

### Why IOException instead of CancellationException for dead client?
Client death is an exceptional failure condition, not a graceful cancellation. The operation failed due to external error, not user request. This ensures the `.catch { }` block emits an Error event (not cancelled), which correctly represents the failure to the user.

### Why no callback timeout?
User may be away from device (locked screen, phone call, etc.) for extended periods. Adding a timeout would cancel legitimate operations. If timeout is needed, it should be:
- In the UI layer (dialog-level "are you still there?" prompt)
- Optional and configurable by user
- Not hardcoded in IPC infrastructure layer

Android already handles stuck IPC calls via binder transaction limits (~5-10s) and ANR detection on main thread.
