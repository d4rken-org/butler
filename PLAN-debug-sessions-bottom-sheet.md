# Debug Sessions Bottom Sheet & Session Viewer

## Context

With `DebugSessionManager` and the typed `DebugSession` model in place, the "Debug log storage" settings item does nothing (`onClick = {}`). This plan:

1. Introduces 4 session states: `Recording` → `Compressing` → `Ready` / `Failed`
2. Centralizes compression in `DebugSessionManager` (not per-ViewModel)
3. Adds a **bottom sheet** listing all sessions with state-appropriate UI and actions
4. Evolves **RecorderScreen** buttons to Delete / Keep / Share
5. Shows compression progress with spinners in both the bottom sheet and RecorderActivity

`RecorderActivity` stays as a standalone Activity — used by RecorderManager auto-launch and by the bottom sheet.

## 1. `DebugSession.kt` — 4 states

**File:** `app/.../common/debug/recorder/core/DebugSession.kt`

```kotlin
sealed interface DebugSession {
    data class Recording(val logDir: File, val startTime: Instant, val currentSize: Long) : DebugSession
    data class Compressing(val sessionDir: File, val size: Long) : DebugSession
    data class Ready(val sessionDir: File, val size: Long) : DebugSession
    data class Failed(val sessionDir: File, val size: Long) : DebugSession
}
```

- `Recording` — active recording
- `Compressing` — recording stopped, zip being created in background
- `Ready` — zip exists, session ready for sharing
- `Failed` — orphaned session dir (crash during recording or compression)

## 2. `DebugSessionManager.kt` — Centralized compression

**File:** `app/.../common/debug/recorder/core/DebugSessionManager.kt`

### New field

```kotlin
private val _compressingDirs = MutableStateFlow<Map<File, Long>>(emptyMap())  // dir → size
```

### State — add `compressingSessions`

```kotlin
data class State(
    val activeSession: DebugSession.Recording? = null,
    val compressingSessions: List<DebugSession.Compressing> = emptyList(),
    val readySessions: List<DebugSession.Ready> = emptyList(),
    val failedSessions: List<DebugSession.Failed> = emptyList(),
    val shortRecordingWarning: ShortRecordingWarning? = null,
)
```

### State flow — 3-way combine

```kotlin
val state: Flow<State> = combine(
    recorderManager.state,
    _sessions,
    _compressingDirs,
) { recState, sessions, compressing ->
    State(
        activeSession = if (recState.isRecording) { ... } else null,
        compressingSessions = compressing.map { (dir, size) ->
            DebugSession.Compressing(sessionDir = dir, size = size)
        },
        readySessions = sessions.ready,
        failedSessions = sessions.failed,
        shortRecordingWarning = ...,
    )
}
```

### `stopRecording()` — always start compression

```kotlin
suspend fun stopRecording(...): File? {
    val result = recorderManager.stopRecorder(showResult, force, warningOrigin)
    if (result != null) startCompression(result)
    return result
}
```

### `startCompression()` — new private method

```kotlin
private fun startCompression(sessionDir: File) {
    val size = sessionDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    _compressingDirs.update { it + (sessionDir to size) }
    appScope.launch(dispatcherProvider.IO) {
        try {
            debugLogZipper.zipAndGetUri(sessionDir)
        } catch (e: Exception) {
            log(TAG, ERROR) { "Compression failed for $sessionDir: ${e.asLog()}" }
        } finally {
            _compressingDirs.update { it - sessionDir }
            refreshSessions()
        }
    }
}
```

### `refreshSessions()` — skip compressing dirs

```kotlin
suspend fun refreshSessions() {
    val activeDir = recorderManager.state.first().currentLogDir
    val compressingDirs = _compressingDirs.value.keys

    for (entry in logRoot.listFiles()) {
        if (entry == activeDir) continue
        if (entry.isDirectory && entry in compressingDirs) continue  // Tracked separately
        if (entry.isDirectory && hasCorrespondingZip) → Ready(sessionDir=entry, size=dirWalk)
        if (entry.isDirectory && !hasCorrespondingZip) → Failed(sessionDir=entry, size=dirWalk)
        if (entry.isFile && entry.extension == "zip" && !dirExists) → Ready(sessionDir=inferredDir, size=zipSize)
    }
}
```

### `awaitReady()` — new method for contact form

```kotlin
suspend fun awaitReady(sessionDir: File): DebugSession.Ready {
    return state.map { it.readySessions.find { s -> s.sessionDir == sessionDir } }
        .filterNotNull()
        .first()
}
```

### `zipSession()` → remove

No longer needed — compression is automatic. Contact form uses `awaitReady()` instead.

### `_sessions` internal model rename

```kotlin
private data class Sessions(
    val ready: List<DebugSession.Ready> = emptyList(),
    val failed: List<DebugSession.Failed> = emptyList(),
)
```

## 3. `RecorderViewModel.kt` — Observe compression state

**File:** `app/.../common/debug/recorder/ui/result/RecorderViewModel.kt`

### Remove local zipping

The init block no longer calls `debugLogZipper.zipAndGetUri()`. Instead it:
1. Lists log files, calculates sizes (same as before)
2. Starts observing `sessionManager.state` for this session

### Observe session state

```kotlin
sessionManager.state
    .map { sessState ->
        when {
            sessState.compressingSessions.any { it.sessionDir == sessionPath } -> SessionPhase.Compressing
            sessState.readySessions.any { it.sessionDir == sessionPath } -> SessionPhase.Ready
            sessState.failedSessions.any { it.sessionDir == sessionPath } -> SessionPhase.Failed
            else -> SessionPhase.Compressing  // Not yet tracked, race window
        }
    }
    .distinctUntilChanged()
    .onEach { phase ->
        when (phase) {
            SessionPhase.Compressing -> stater.updateBlocking { copy(isWorking = true) }
            SessionPhase.Ready -> {
                val zipFile = File(sessionPath!!.parentFile, "${sessionPath.name}.zip")
                stater.updateBlocking {
                    copy(compressedFile = zipFile, compressedSize = zipFile.length(), isWorking = false)
                }
            }
            SessionPhase.Failed -> stater.updateBlocking { copy(isWorking = false) }
        }
    }
    .launchInViewModel()
```

### Button actions — Delete / Keep / Share

- **`delete()`** (was `discard()`): delete zip + session dir, refresh sessions, emit closeEvent
- **`keep()`** (was `save()`): just emit closeEvent — no deletion, session stays
- **`share()`**: unchanged behavior, but only enabled when `!isWorking`

Remove `debugLogZipper` injection from constructor (no longer zips locally). Keep it only for `share()` → `getUriForZip()`.

Wait — `share()` needs to create the share intent with the zip URI. It still uses `debugLogZipper.getUriForZip()`. So keep the injection.

## 4. `RecorderScreen.kt` — Button labels

**File:** `app/.../common/debug/recorder/ui/result/RecorderScreen.kt`

`ActionButtons` parameter rename: `onCancelClick` → `onDeleteClick`, `onSaveClick` → `onKeepClick`.

Labels:
- Left (OutlinedButton): new string `debug_log_screen_delete_action` = "Delete"
- Middle (FilledTonalButton): new string `debug_log_screen_keep_action` = "Keep"
- Right (Primary): existing `general_share_action`

Wire in RecorderScreenHost: `onDeleteClick = { vm.delete() }`, `onKeepClick = { vm.keep() }`.

## 5. New `DebugSessionsBottomSheet.kt`

**File:** `app/.../main/ui/settings/support/DebugSessionsBottomSheet.kt`

Follows `FileInfoBottomSheet` pattern: `ModalBottomSheet` with `LocalInspectionMode` guard.

### Data model

```kotlin
data class DebugSessionItem(
    val session: DebugSession,
    val displayName: String,
    val lastModified: Instant,
    val size: Long,
)
```

### Composable signature

```kotlin
@Composable
fun DebugSessionsBottomSheet(
    sessions: List<DebugSessionItem>,
    onDismiss: () -> Unit,
    onSessionClick: (DebugSessionItem) -> Unit,
    onDeleteSession: (DebugSessionItem) -> Unit,
    onStopRecording: () -> Unit,
)
```

### Row layout by session type

| State | Leading icon | Info | Row click | End icon |
|---|---|---|---|---|
| `Recording` | `FiberManualRecord` (red) | name + "Recording…" + size | — | `Stop` → `onStopRecording` |
| `Compressing` | `CircularProgressIndicator` (small) | name + "Compressing…" + size | — | — |
| `Ready` | `CheckCircle` | name + date + size | `onSessionClick` | `Delete` → `onDeleteSession` |
| `Failed` | `ErrorOutline` | name + "Failed" + date + size | — | `Delete` → `onDeleteSession` |

Previews: mixed states, empty, ready-only.

## 6. `SupportScreenViewModel.kt` — Expand for bottom sheet

**File:** `app/.../main/ui/settings/support/SupportScreenViewModel.kt`

### Expand State

```kotlin
data class State(
    val isRecording: Boolean,
    val logPath: File?,
    val debugLogFolderStats: DebugLogFolderStats = DebugLogFolderStats(0, 0L),
    val sessionItems: List<DebugSessionItem> = emptyList(),
)
```

Build `sessionItems` from all 4 session types in `DebugSessionManager.State`:
- Active → item with Recording session
- Compressing → items with Compressing sessions
- Ready → items with Ready sessions
- Failed → items with Failed sessions

Sort: active first, then compressing, then by lastModified descending.

Stats include all non-active sessions (compressing + ready + failed counts and sizes).

### Actions

```kotlin
fun deleteSession(item: DebugSessionItem) = launch { sessionManager.deleteSession(item.session) }
fun stopRecording() = launch { sessionManager.stopRecording(showResult = false, force = true) }
```

Add `openSessionEvent = SingleEventFlow<String>()`:
```kotlin
fun openSession(item: DebugSessionItem) {
    val ready = item.session as? DebugSession.Ready ?: return
    openSessionEvent.tryEmit(ready.sessionDir.path)
}
```

## 7. `SupportScreen.kt` — Add bottom sheet

**File:** `app/.../main/ui/settings/support/SupportScreen.kt`

### SupportScreenHost

Add `LaunchedEffect` for `vm.openSessionEvent` → launch `RecorderActivity`:
```kotlin
LaunchedEffect(vm.openSessionEvent) {
    vm.openSessionEvent.collect { path ->
        context.startActivity(RecorderActivity.getLaunchIntent(context, path))
    }
}
```

Wire callbacks: `onDeleteSession`, `onOpenSession`, `onStopRecording`.

### SupportScreen

- Add `var showSessionsSheet` and `var sessionToDelete` local state
- "Debug log storage" item: `onClick = { showSessionsSheet = true }`
- Show `DebugSessionsBottomSheet` when `showSessionsSheet`
  - `onSessionClick` → dismiss + `onOpenSession`
  - `onDeleteSession` → `sessionToDelete = item` (confirm dialog)
  - `onStopRecording` → `onStopRecording()` (no dismiss, session transitions to Compressing in list)
- Per-session delete confirmation dialog
- Keep "Delete all debug logs" settings item unchanged

Update function signature with new callbacks. Update previews.

## 8. `SupportContactFormViewModel.kt` / `SupportContactFormScreen.kt` — Rename types

**Files:** `app/.../support/contactform/SupportContactFormViewModel.kt`, `SupportContactFormScreen.kt`

Rename `Completed` → `Ready`:
- `selectedSession: MutableStateFlow<DebugSession.Ready?>`
- `LogPickerState.sessions: List<DebugSession.Ready>`, `selectedSession: DebugSession.Ready?`
- All callbacks and preview data

### `zipAndSelect()` → uses `awaitReady()`

```kotlin
private suspend fun zipAndSelect(sessionDir: File?) {
    if (sessionDir != null) {
        val ready = sessionManager.awaitReady(sessionDir)
        selectedSession.value = ready
    }
}
```

### `buildAttachment()` → derive zip from sessionDir

```kotlin
val dir = selectedSession.value?.sessionDir ?: return null
val zipFile = File(dir.parentFile, "${dir.name}.zip")
if (!zipFile.exists()) return null
return debugLogZipper.getUriForZip(zipFile)
```

## 9. String resources

**File:** `app/src/main/res/values/strings.xml`

```xml
<string name="support_debuglog_sessions_title">Debug log sessions</string>
<string name="support_debuglog_sessions_empty">No debug log sessions</string>
<string name="support_debuglog_session_failed_label">Failed</string>
<string name="support_debuglog_session_recording_label">Recording…</string>
<string name="support_debuglog_session_compressing_label">Compressing…</string>
<string name="support_debuglog_session_delete_confirm_title">Delete debug session?</string>
<string name="support_debuglog_session_delete_confirm_message">This will permanently delete this debug log session.</string>
<string name="debug_log_screen_delete_action">Delete</string>
<string name="debug_log_screen_keep_action">Keep</string>
```

## What stays unchanged

- `RecorderActivity.kt` — continues launching from RecorderManager and bottom sheet
- `RecordingBanner*` — uses BannerViewModel, unaffected
- `App.kt` — already migrated
- `ShortRecordingDialog.kt`, `RecorderConsentDialog.kt` — pure presentation

## Verification

1. Build: `./gradlew :app:compileFossDebugKotlin --no-daemon`
2. Test on device:
   - Start recording → stop → session enters Compressing with spinner in bottom sheet
   - Compression completes → session becomes Ready (checkmark icon)
   - Bottom sheet: active recording row has stop icon, compressing row has spinner, ready row is clickable
   - Click ready session → RecorderActivity opens with session details
   - RecorderActivity: If opened during compression → shows spinner until ready
   - RecorderActivity: Delete removes session + closes, Keep just closes, Share works when ready
   - Force-kill during compression → session appears as Failed on restart
   - Contact form: stop recording → waits for compression → session auto-selected
   - "Delete all" settings item still works
