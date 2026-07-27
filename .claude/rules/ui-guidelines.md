# UI Guidelines

## User Interface

- Full Jetpack Compose with Material 3.
- Custom theming system (`ButlerTheme`, `ButlerColors`).
- Edge-to-edge display support.
- Use icons out of the `androidx.compose.material.icons.twotone` package where possible.
- When creating compose previews, use the `@Preview2` annotation, and wrap the UI element in a `PreviewWrapper`.

## Pane Edge Padding

Horizontal insets from the pane edge come from `WorkspacePaddings` (`app-workspace`, package `eu.darken.butler.workspace.ui.common`):

- `BarHorizontal` (16.dp): applied automatically by `FloatingBarStack` to every bar. Never add `Modifier.padding(horizontal = …)` to a `FloatingBar` — override the stack's `horizontalPadding` instead.
- `ContentHorizontal` (12.dp): page content (lists, card columns) below/behind the bars.

Exceptions: the Templates picker layout (24.dp), the Explorer grid branch (2.dp) and the Apps grid branch (8.dp). Card-internal paddings are not pane-edge insets and stay as they are.

## MVVM with Custom ViewModel Hierarchy

Butler uses a layered ViewModel hierarchy (`ViewModel1`…`ViewModel4`) where each level adds capabilities.

New ViewModels should extend **`ViewModel3`** (no navigation needed) or **`ViewModel4`** (with navigation). Uses Hilt assisted injection for workspace ID parameters.

## Host/Page Pattern

Screens follow a two-composable pattern that separates side effects from presentation:

**Page composable** (e.g., `ExplorerWorkspacePage`): Pure presentation. Accepts `Flow<State>` parameters (not ViewModel). Collects with `collectAsState()`. Contains no side effects. Previewable with mock `flowOf()` data.

**Host composable** (e.g., `ExplorerWorkspacePageHost`): Gets ViewModel via `hiltViewModel()` with assisted injection. Handles side effects (event handlers, permission launchers, intent launchers). Connects `ErrorEventHandler(vm)` and `NavigationEventHandler(vm)`. Passes state flows to the Page.

```kotlin
// Host — side effects and ViewModel wiring
@Composable
fun MyWorkspacePageHost(
    id: Workspace.Id,
    design: WorkspaceDesign,
    vm: MyWorkspaceViewModel = hiltViewModel(
        key = id.longTag,
        creationCallback = { factory: MyWorkspaceViewModel.Factory -> factory.create(id = id) }
    ),
) {
    ErrorEventHandler(vm)
    NavigationEventHandler(vm)

    MyWorkspacePage(
        design = design,
        stateSource = vm.state,
    )
}

// Page — pure presentation, previewable
@Composable
fun MyWorkspacePage(
    design: WorkspaceDesign = WorkspaceDesign(),
    stateSource: Flow<MyWorkspaceViewModel.State>,
) {
    val state by stateSource.collectAsState(initial = null)
    state ?: return
    // ... render UI
}
```

## State Patterns

Two patterns are used for ViewModel state:

**Single State data class** — one `data class State(...)` with all fields having defaults. Updated via `.copy()`. Used when the screen always has meaningful content (e.g., Explorer).

```kotlin
data class State(
    val items: List<Item>? = null,
    val error: Throwable? = null,
    val selectionState: SelectionState = SelectionState(),
)
```

**Sealed interface** — distinct variants like `Initializing`, `Error`, `Ready`. Used when the screen has clearly different phases (e.g., Searcher). Makes loading/error states more explicit in the UI via `when` expressions.

```kotlin
sealed interface State {
    data object Initializing : State
    data class Error(val error: Throwable) : State
    data class Ready(
        val items: List<Item> = emptyList(),
        // ...
    ) : State
}
```

Both are valid approaches — choose based on whether the screen has distinct lifecycle phases.
