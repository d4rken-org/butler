# Technical Patterns

## General

- Single Activity architecture with Compose Navigation3.
- Reactive programming with Kotlin Flow and StateFlow.
- Centralized error handling with `ErrorEventHandler`.
- DataStore-based settings with kotlinx serialization.
    - When accessing settings values, use the `.value()` extension function instead of `.flow.first()`
    - Example: `searcherSettings.defaultSearchPath.value()` not `searcherSettings.defaultSearchPath.flow.first()`
    - For setting values use: `searcherSettings.someSetting.value(newValue)`
- Jetpack Compose for UI.
- Hilt for dependency injection.
- Kotlin Coroutines & Flow for async operations.
- KotlinX for JSON serialization.
- Coil for image loading.
- Room for database operations.
- Use `FlowCombineExtensions` instead of nesting multiple combine statements.
- Prefer Kotlin standard library types over Java equivalents:
    - Use `kotlin.Uuid` instead of `java.util.UUID`
    - Use `kotlin.time.Instant` instead of `java.time.Instant`
    - Use `kotlin.time.Duration` instead of `java.time.Duration`
    - Use Kotlin collections and their extension functions
- Check if `@OptIn` annotations are actually necessary before adding them:
    - Many experimental APIs (like `ExperimentalMaterial3Api`) are already enabled project-wide via gradle compile flags (`freeCompilerArgs`)
    - Only add `@OptIn` if you get a compilation error without it

## Dependency Injection

- Hilt/Dagger throughout the application.
- `@AndroidEntryPoint` for Activities/Fragments.
- `@HiltViewModel` for ViewModels.
- Modular DI setup across different modules.

## Business Logic

- Abstract path system (`APath`, `RawPath`).
    - `APath` offers path segment infos via `segments`. Use that instead of path splitting.
- Gateway pattern for different file access methods.
- Support for root, ADB, and shell operations.

## Type Converters and Serialization

- When creating type converters or serialization tools, consider the scope:
    - **Global types** (e.g., `Instant`, `Duration`, `Uuid`): Place converters in the `app-common` module for reuse across the entire application
    - **Workspace-specific types**: Place converters in the respective workspace module (e.g., editor-specific converters in `app-workspace-editor`)
    - This ensures proper code organization and prevents duplication

## Logging

Butler uses a custom logging system (`Logging.kt`) for comprehensive debugging and monitoring.

### Required Imports

```kotlin
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
```

### Priority Levels

- **VERBOSE (2)**: Most detailed logging for deep debugging
- **DEBUG (3)**: General debugging information (default priority)
- **INFO (4)**: Important informational messages and milestones
- **WARN (5)**: Warning conditions that need attention
- **ERROR (6)**: Error conditions and exceptions
- **ASSERT (7)**: Critical assertions and "WTF" moments

### Tag Conventions

Create hierarchical tags following the pattern: `Module:Component:Instance:Page`

```kotlin
// Simple component
private val tag = logTag("ComponentName")

// Module with component
private val tag = logTag("Editor", "Engine")

// Workspace with instance ID
private val tag = logTag("Explorer", "Workspace", id.shortTag)

// ViewModel with page context
private val tag = logTag("Searcher", "Workspace", id.shortTag, "Page")
```

Tags are automatically prefixed with "BUTLER:" creating output like: `BUTLER:Editor:Engine`

### Usage Patterns

```kotlin
// Basic logging (uses DEBUG priority by default)
log(tag) { "Opening file: $filePath" }

// Informational logging
log(tag, INFO) { "Successfully initialized with file: $filePath" }

// Warning logging
log(tag, WARN) { "Cannot insert text - no resources available" }

// Error logging with exception details
try {
    // operation
} catch (e: Exception) {
    log(tag, ERROR) { "Failed to save file - ${e.asLog()}" }
}
```

### Best Practices

- Always use lazy evaluation with lambda: `{ "message" }` for performance
- Use `e.asLog()` extension for exception logging to get full stack traces
- Use appropriate priority levels: ERROR for exceptions, WARN for concerning conditions, INFO for milestones, DEBUG for general logging
- Follow hierarchical tag naming for consistent categorization
- ViewModels should include workspace ID in tags when applicable
- Keep log messages concise but descriptive
