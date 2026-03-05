# Remove `mapNotNull` from `ViewModel2.asStateFlow()`

## Context

`ViewModel2.asStateFlow()` uses `mapNotNull { it }` to filter the initial `null` emission from `stateIn()`, returning `Flow<T>`. This is:
1. **Redundant** — all consumers already handle null via `collectAsState(initial = null)` + `state ?: return`
2. **Type-unsafe** — if `T` is nullable, `mapNotNull` silently swallows legitimate null values
3. **Misleading** — hides the null initial state at the wrong abstraction layer

Goal: Remove `mapNotNull`, change return type to `Flow<T?>`, and propagate the type change to consumers.

## Changes

### Step 1: Core function (`ViewModel2.kt`)

**File:** `app-common/src/main/java/eu/darken/butler/common/ui/ViewModel2.kt`

```kotlin
// Before:
fun <T> Flow<T>.asStateFlow(defaultValue: T? = null): Flow<T> = stateIn(
    vmScope,
    started = SharingStarted.WhileSubscribed(5000),
    initialValue = defaultValue,
).mapNotNull { it }

// After:
fun <T> Flow<T>.asStateFlow(defaultValue: T? = null): Flow<T?> = stateIn(
    vmScope,
    started = SharingStarted.WhileSubscribed(5000),
    initialValue = defaultValue,
)
```

Remove unused `mapNotNull` import.

### Step 2: Page composable parameter types

Only Pages that take `Flow<T>` parameters from VMs using `asStateFlow()` need updating. 25 VMs use `asStateFlow()` but most use Pattern 2 (Host collects, passes non-null State to Screen) — those are unaffected.

**Pattern 1 Pages** (receive Flow directly from Host):

**`ExplorerWorkspacePage.kt`** (`app-workspace-explorer/.../explorer/ExplorerWorkspacePage.kt:104`)
- `mainStateSource: Flow<State>` → `Flow<State?>`
- `clipboardStateSource: Flow<ClipboardDisplayState>` → `Flow<ClipboardDisplayState?>`
- `operationsStateSource: Flow<OperationsDisplayState>` → `Flow<OperationsDisplayState?>`
- Update `collectAsState` calls at lines 120-121 to use `initial = null` and add null coalescing

**`SearcherWorkspacePage.kt`** (`app-workspace-searcher/.../search/SearcherWorkspacePage.kt:96`)
- `clipboardStateSource: Flow<ClipboardDisplayState>` → `Flow<ClipboardDisplayState?>`
- `operationsStateSource: Flow<OperationsDisplayState>` → `Flow<OperationsDisplayState?>`
- (`stateSource` is NOT affected — `SearcherWorkspaceViewModel.state` is `StateFlow<State>` from `stateIn()` directly, not our custom `asStateFlow()`)
- Update `collectAsState` calls at lines 109-110

### Step 3: Handle nullable auxiliary flows in Pages

For auxiliary flows (clipboard, operations), change from non-null initial to null-safe pattern:

```kotlin
// Before:
val clipboardState by clipboardStateSource.collectAsState(ClipboardDisplayState())

// After:
val clipboardState by clipboardStateSource.collectAsState(initial = null)
```

Then use `clipboardState ?: ClipboardDisplayState()` (or equivalent) where the value is used. Same for `operationsState`.

### What does NOT change

- **25 ViewModel properties** — all use type inference, types auto-update to `Flow<T?>`
- **Pattern 2 Hosts** (settings, developer, templates, etc.) — already do `collectAsState(initial = null)` + `state?.let { Screen(state = it) }`, no changes needed
- **Pattern 1 Hosts** (ExplorerWorkspacePageHost, SearcherWorkspacePageHost) — pass `vm.state` directly, type-compatible after Page parameter update
- **Preview functions** — `flowOf(state)` returns `Flow<State>` which is subtype of `Flow<State?>` (covariance)
- **Stdlib `MutableStateFlow.asStateFlow()`** — completely separate function, not affected
- **EditorWorkspacePage** — its `clipboardStateSource` comes from `EditorWorkspaceVM.clipboard` which does NOT use our custom `asStateFlow()`
- **AppsWorkspacePage, SaverWorkspacePage** — their VMs don't use our custom `asStateFlow()` for any flow passed to the Page

## Verification

1. Build: `./gradlew :app:compileFossDebugKotlin --no-daemon` — compiler catches any remaining type mismatches
2. Run tests: `./gradlew testDebugUnitTest` — verify no behavioral regressions
