---
paths: ["**/ui/**", "**/common/compose/**", "**/common/theming/**", "**/common/navigation/**", "**/common/error/**", "**/common/settings/**", "**/*ViewModel.kt"]
---

# UI Guidelines

## User Interface

- Full Jetpack Compose with Material 3; custom theming via `ButlerTheme` / `ButlerColors`; edge-to-edge.
- Use icons from the `androidx.compose.material.icons.twotone` package where possible.
- Compose previews use the `@Preview2` annotation and wrap the UI element in a `PreviewWrapper`.

## Pane Edge Padding

Spacing comes from `WorkspacePaddings` (`app-workspace`, package `eu.darken.butler.workspace.ui.common`). Pick by what the item is, not by which screen it is on:

- `BarHorizontal` (16.dp): applied automatically by `FloatingBarStack` to every bar, and to floating chrome that lines up with one (the Explorer error cards). Never add `Modifier.padding(horizontal = …)` to a `FloatingBar` — override the stack's `horizontalPadding` instead.
- `ContentHorizontal` (12.dp): full-width page content (lists, card columns) below/behind the bars.
- `GridHorizontal` / `GridGutter` (both 4.dp): tile grids. The outer inset matches the gutter, so the side margins read as wide as the gaps between tiles. Change them together.
- `ListGap` (8.dp) between standalone cards, `ListGapDense` (4.dp) between full-bleed rows that carry their own interior padding.
- `ScreenHorizontal` (24.dp): full-window surfaces that are not a workspace pane (workspace manager, Upgrade).

Card-internal paddings are not pane-edge insets and stay as they are; toolbar cards use `CutoutCardDefaults`. The Editor is deliberately full-bleed (0) because `LazyTextEditor` supplies its own gutter, and the Searcher results list spaces rows by the user's density setting rather than a token.

## Pull to Refresh

Workspace pages use `WorkspacePullToRefreshBox` (`app-workspace`, package `eu.darken.butler.workspace.ui.common`) and pass their top `FloatingBarStackState`. Never use `PullToRefreshBox` + `PullToRefreshDefaults.Indicator` directly: the default indicator translates vertically as the user pulls, so it emerges from behind the floating bar stack.

## MVVM with Custom ViewModel Hierarchy

Butler uses a layered ViewModel hierarchy (`ViewModel1`…`ViewModel4`) where each level adds capabilities.

New ViewModels extend **`ViewModel3`** (no navigation needed) or **`ViewModel4`** (with navigation), using Hilt assisted injection for workspace ID parameters.

## Host/Page Pattern

Screens follow a two-composable pattern separating side effects from presentation:

- **Page** (e.g., `SearcherWorkspacePage`): pure presentation. Accepts `Flow<State>` parameters (not the ViewModel), collects with `collectAsState()`, contains no side effects, previewable with `flowOf()` mock data.
- **Host** (e.g., `SearcherWorkspacePageHost`): gets the ViewModel via `hiltViewModel()` with assisted injection (`key = id.longTag`, factory `creationCallback`), wires the event handlers used by the screen (`NavigationEventHandler(vm)`, `ErrorEventHandler(vm)` where applicable) and other side effects (permission/intent launchers), and passes state flows to the Page.

Reference implementation: `SearcherWorkspacePageHost` + `SearcherWorkspacePage` in
`app-workspace-searcher/src/main/java/eu/darken/butler/searcher/ui/search/SearcherWorkspacePage.kt`. (Explorer's Page predates the pattern and takes the ViewModel directly — don't copy it for new screens.)

## State Patterns

Two patterns for ViewModel state — choose by whether the screen has distinct lifecycle phases:

- **Single `data class State`** with defaulted fields, updated via `.copy()` — when the screen always has meaningful content. Reference: `ExplorerWorkspaceViewModel.State`.
- **Sealed interface** with variants like `Initializing` / `Error` / `Ready` — when the screen has clearly different phases; makes loading/error states explicit in `when` expressions. Reference: `SearcherWorkspaceViewModel.State`.
