# Architecture Overview

Module list: see `settings.gradle`. Build flavors: see `app/build.gradle.kts`.

## Dependency Diagram

Each layer depends only on layers below it. Arrows point downward (dependency direction).

```
Layer 6:  app
            │
Layer 4:  workspace-explorer, workspace-searcher, workspace-editor,
          workspace-apps, workspace-developer, workspace-saver, workspace-history,
          workspace-templates
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

- **Workspace isolation**: Workspace implementation modules (`explorer`, `searcher`, `editor`, `apps`, `developer`, `saver`, `history`, `templates`) must NOT depend on each other.
- **Inter-workspace communication**: Goes through `WorkspaceRemote` events and actions, never direct imports. Shared contracts (`*Arguments`, `PickerConfig`/`PickerConstraint`, search filter/query types, apps view/tag types) live in `app-workspace` under `eu.darken.butler.workspace.contracts.<feature>`. A workspace module may import `eu.darken.butler.workspace.*` but never `eu.darken.butler.<other-feature>.*`. See `architecture-modal-workspaces.md` for the modal workspace pattern.
- **Workspace self-registration**: Each workspace module contributes its own bindings via Hilt multibinding; aggregation happens in `:app`'s dependency graph, with no central wiring code:
    - **Factory**: `@Provides @IntoMap @WorkspaceTypeKey(Workspace.Type.X)` returning `WorkspaceFactory<*>` (nested `FactoryModule` in each `*Workspace.kt`).
    - **Template** (tile in the Templates picker): `@Provides @IntoSet` returning `WorkspaceTemplate` (nested `TemplateModule` in each `*WorkspaceTemplate.kt`). Modules without a user-creatable template (`saver`, app-details, templates itself) simply don't contribute one. Templates self-describe `sortOrder`, `isQuickCreate` (FAB dropdown), and reactive `availability` (e.g. developer mode gating).
    - **Page host**: `@Provides @IntoMap @WorkspaceTypeKey(...)` returning `WorkspacePageHostEntry` (stateless composable delegate); distributed to the UI via the `LocalWorkspacePageHosts` CompositionLocal.
    - **Hard requirement**: `:app`'s direct `implementation(project(...))` dependency on each workspace module is what keeps these contributions in the Hilt graph — removing one silently drops its bindings. `WorkspaceRegistryValidator` asserts registry completeness at startup in debug builds.
    - `Workspace.Type.icon`/`label`/`defaultArguments` intentionally stay as exhaustive `when`s in `app-workspace`: they reference only module-local classes/resources, and exhaustiveness makes the compiler enforce updates when a type is added.
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
