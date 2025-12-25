# Architecture Overview

## Build Flavors

- **FOSS**: Open source version without Google Play dependencies.
- **GPLAY**: Google Play version with additional features.

## Module Structure

### Core Application

- `app`: Main application module with entry point, flavor-specific implementations, and setup flow.

### Foundation Modules

- `app-common`: Core shared utilities, base architecture components, custom ViewModel hierarchy, theming system.
- `app-common-test`: Testing utilities, helpers, and base test classes for all modules.

### Platform Integration Modules

- `app-common-io`: File I/O operations, abstract path system (APath), gateway pattern for file access methods.
- `app-common-root`: Root access functionality and root-based file operations.
- `app-common-adb`: Android Debug Bridge integration via Shizuku API.
- `app-common-shell`: Shell operations and reactive command execution with FlowShell.
- `app-common-pkgs`: Package management utilities and package event handling.

### Workspace Modules

- `app-workspace`: Core workspace framework, base classes, and tab-like workspace management.
- `app-workspace-explorer`: File browsing workspace with navigation, file operations, sorting/filtering.
- `app-workspace-searcher`: File search workspace with search engine, filters, and result caching.
- `app-workspace-editor`: Text editing workspace with chunked buffer system for large files.
- `app-workspace-templates`: Workspace template management and type switching.

## Modal Workspace Pattern

Butler supports **modal workspaces** - workspaces that render as full-screen overlays instead of tabs. This pattern enables workspace-to-workspace interactions like file/folder pickers, while maintaining full workspace capabilities.

### Core Concept: Sub-Workspaces

A **sub-workspace** is a workspace created by another workspace to return a result (e.g., Explorer picker launched by Searcher). Sub-workspaces:

- Render as full-screen modals that block background interaction
- Maintain full workspace capabilities (navigation, operations, permissions)
- Automatically close when their parent workspace closes
- Return results via `WorkspaceEvent.PickerResult`

**Architectural Principle:** The domain layer exposes workspace relationships (`callerWorkspaceId`), the UI layer decides presentation (modal vs tab).

### Creating a Result-Returning Workspace

**1. Implement `ArgumentsForResult` Interface**

```kotlin
@Parcelize
data class ExplorerPickerArguments(
    val startPath: APath<*>? = null,
    val pickerMode: PickerMode = PickerMode.DIRECTORY,
    override val callerWorkspaceId: Workspace.Id? = null,  // Required
) : Workspace.ArgumentsForResult {
    @IgnoredOnParcel
    override val type: Workspace.Type = Workspace.Type.EXPLORER
}
```

**Key Points:**

- Inherit from `Workspace.ArgumentsForResult` (not just `Workspace.Arguments`)
- Override `callerWorkspaceId` property to expose the calling workspace
- This enables generic parent-child tracking across all workspace types

**2. Expose Relationship in Workspace.Info**

```kotlin
override val info: Flow<Workspace.Info> = combine(
    // ... your state flows ...
) { /* ... */ ->
    Workspace.Info(
        id = id,
        type = type,
        title = /* ... */,
        callerWorkspaceId = pickerConfig?.callerWorkspaceId,  // Expose relationship
    )
}
```

**3. Return Results via Convenience Functions**

```kotlin
import eu.darken.butler.workspace.core.returnResult
import eu.darken.butler.workspace.core.cancelResult

// In your confirmation method - return result and close:
workspaceRemote.returnResult(
    WorkspaceEvent.PickerResult(
        workspaceId = id,
        callerWorkspaceId = config.callerWorkspaceId,
        selectedPaths = selectedPaths
    )
)

// In your cancellation method - emit cancellation and close:
workspaceRemote.cancelResult(
    workspaceId = id,
    callerWorkspaceId = config.callerWorkspaceId,
)
```

**Note:** The `returnResult()` and `cancelResult()` convenience functions combine event emission with automatic workspace closure. For more complex flows requiring multiple events before closing, use `workspaceRemote.emitEvent()` and `workspaceRemote.execute(Close())` separately.

### Launching a Modal Workspace

```kotlin
// In calling workspace (e.g., SearcherWorkspaceViewModel):

// 1. Create the modal workspace
val result = workspaceRemote.execute(
    WorkspaceAction.Create(
        type = Workspace.Type.EXPLORER,
        arguments = ExplorerPickerArguments(
            startPath = currentPath,
            pickerMode = PickerMode.DIRECTORY,
            callerWorkspaceId = id  // Pass your workspace ID
        )
    )
) as WorkspaceAction.Create.Result

// 2. Listen for results using convenience extension
import eu.darken.butler.workspace.core.handleResult

workspaceRemote.events
    .handleResult<WorkspaceEvent.PickerResult>(callerWorkspaceId = id) { result ->
        // Handle result
        val selectedPath = result.selectedPaths.firstOrNull()
        updateSearchPath(selectedPath)
        // Workspace closes automatically - no manual close needed
    }
    .launchInViewModel()
```

### UI Rendering

The UI layer automatically renders sub-workspaces as modals:

```kotlin
// WorkspacesViewModel.State derives presentation:
val tabWorkspaces: List<Workspace.Info>
    get() = state.infos.filter { !it.isSubWorkspace }  // Normal workspaces

val modalWorkspace: Workspace.Info?
    get() = state.infos.firstOrNull { it.isSubWorkspace }  // Modal overlay
```

**No workspace code changes needed** - the UI layer uses `Workspace.Info.isSubWorkspace` (derived from `callerWorkspaceId != null`) to decide rendering.

### Parent-Child Lifecycle

Parent-child relationships enable automatic cleanup:

```kotlin
// In WorkspaceRepo.execute(WorkspaceAction.Close):
val childWorkspaces = _workspaces.value.filter { ws ->
    val info = ws.info.first()
    info.callerWorkspaceId == action.id  // Find children of closing workspace
}
// Auto-close all child workspaces when parent closes
```

**Benefits:**

- Prevents orphaned picker workspaces
- No manual tracking needed
- Works for any `ArgumentsForResult` implementation

### Example Use Cases

1. **File/Folder Picker** (Implemented)
    - Searcher launches Explorer picker to select search directory
    - Full Explorer features: navigation, permissions, folder creation
    - Returns selected path, closes automatically

2. **File Picker for Editor** (Future)
    - Editor launches Explorer picker to open files
    - Multi-select support for opening multiple files

3. **Template Picker** (Future)
    - Any workspace can launch Templates picker to switch types
    - Returns selected template, workspace morphs

### Best Practices

**Domain Layer:**

- Don't expose UI concepts like `presentationMode`, `displayStyle`, `renderType`
- Do expose domain relationships like `callerWorkspaceId`, `parentId`, `ownerWorkspaceId`
- Let UI layer derive presentation from domain data

**Result Events:**

- All result events implement `WorkspaceEvent.ResultEvent` interface
- Use specific event types for different result payloads (e.g., `PickerResult`)
- Include both `workspaceId` and `callerWorkspaceId` for robust routing
- Use `returnResult()` convenience function for common "return-and-close" pattern
- Use `cancelResult()` to emit cancellation event when dismissed without result
- For complex flows (preview, validation), emit events separately and close manually

**Handling Results:**

- Use `handleResult<T>()` flow extension for automatic filtering and type-safe handling
- No manual workspace close needed - `handleResult()` filters terminal events
- For multiple result types, chain multiple `handleResult()` calls
- Example: `.handleResult<PickerResult>(id) { /* handle */ }.launchIn(scope)`

**Naming:**

- Arguments: `[Type]PickerArguments` (e.g., `ExplorerPickerArguments`)
- Config: `PickerConfig` (stored in workspace instance, not flowed)
- Events: `[Type]Result` (e.g., `PickerResult`)
- All implement `ResultEvent` for consistent handling
