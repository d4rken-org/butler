# Architecture Overview

## Build Flavors

- **FOSS**: Open source version without Google Play dependencies.
- **GPLAY**: Google Play version with additional features.

## Module Structure

### Core Application

- `app`: Main application module with entry point, flavor-specific implementations, and setup flow.

### Foundation Modules

- `app-common`: Core shared utilities, base architecture components, custom ViewModel hierarchy, theming system.
- `app-common-test`: Testing utilities, helpers, and base test classes for all modules (test-only, consumed via `testImplementation`).

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
- `app-workspace-apps`: Application management workspace.
- `app-workspace-developer`: Developer tools workspace.
- `app-workspace-saver`: File save/export workspace.
- `app-workspace-history`: File operation history workspace.
- `app-workspace-templates`: Workspace template management and type switching.

### Provider Modules

- `app-provider-documents`: Android `DocumentsProvider` integration, exposing Butler's storage to other apps via SAF.

## Dependency Diagram

Each layer depends only on layers below it. Arrows point downward (dependency direction).

```
Layer 6:  app
            │
Layer 5:  app-workspace-templates  (aggregates workspace types for type switching)
            │
Layer 4:  workspace-explorer, workspace-searcher, workspace-editor,
          workspace-apps, workspace-developer, workspace-saver, workspace-history
            │
Layer 3:  app-workspace          app-provider-documents
            │
Layer 2:  app-common-io  (integrates root/adb/shell via gateways)
          app-common-pkgs
            │
Layer 1:  app-common-root, app-common-adb, app-common-shell
            │
Layer 0:  app-common
```

`app-common-test` sits outside the production layering: it depends on `app-common` and `app-common-io` and is consumed by all modules via `testImplementation` only.

## Cross-Module Dependency Rules

- **Workspace isolation**: Workspace implementation modules (`explorer`, `searcher`, `editor`, `apps`, `developer`, `saver`, `history`) must NOT depend on each other.
- **Inter-workspace communication**: Goes through `WorkspaceRemote` events and actions, never direct imports. Shared contracts (`*Arguments`, `PickerConfig`/`PickerConstraint`, search filter/query types, apps view/tag types) live in `app-workspace` under `eu.darken.butler.workspace.contracts.<feature>`. A workspace module may import `eu.darken.butler.workspace.*` but never `eu.darken.butler.<other-feature>.*`. See `architecture-modal-workspaces.md` for the modal workspace pattern.
- **Exception — templates**: `app-workspace-templates` depends on the workspace modules whose template factories it aggregates for type switching (currently `explorer`, `searcher`, `editor`, `apps`, `developer`, `history` — not `saver`).
- **File operations**: New modules should depend on `app-common-io` for file operations via `GatewaySwitch`. Never depend directly on `app-common-root`, `app-common-adb`, or `app-common-shell` — those are internal to the gateway layer.

## APath & Gateway Pattern

Butler abstracts all file system access behind the APath/Gateway system so workspace code never uses `java.io.File` directly.

### APath

`APath<Self>` is a sealed interface with two implementations:

- **`LocalPath`**: Wraps `java.io.File`. Used for direct, root, and ADB file access.
- **`SAFPath`**: Represents a Storage Access Framework URI with tree root + path segments. Used for ContentProvider-based access.

Use `path.segments` for path manipulation — never split path strings manually. Use `path.child("name")` and `path.parent` for navigation.

### Gateways

- **`APathGateway<P, PL>`**: Interface defining file operations (lookup, list, read, write, copy, move, delete, create, walk, du) for a path type.
- **`LocalGateway`**: Handles `LocalPath` operations. Supports auto-escalation: tries direct access first, then escalates to ROOT or ADB on permission errors. Workspace code never selects the mode — it's automatic.
- **`SAFGateway`**: Handles `SAFPath` operations via Android's DocumentFile API.
- **`GatewaySwitch`**: Central dispatcher that routes any `APath<*>` to the correct gateway based on path type. Handles cross-type operations (e.g., copy from SAF to local) automatically. **This is what workspace code should inject and use.**

### Usage

```kotlin
// Workspace code injects GatewaySwitch, never individual gateways
@Inject lateinit var gatewaySwitch: GatewaySwitch

// Operations work with any APath type
val files = gatewaySwitch.lookupFiles(somePath)
gatewaySwitch.copy(sourcePath, targetPath)
```
