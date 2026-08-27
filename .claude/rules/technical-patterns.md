# Technical Patterns

## General

- Single Activity architecture with Compose Navigation3.
- Reactive programming with Kotlin Flow and StateFlow.
- Centralized error handling with `ErrorEventHandler`.
- DataStore-based settings with kotlinx serialization.
    - When accessing settings values, use the `.value()` extension function instead of `.flow.first()`
    - Example: `searcherSettings.defaultSearchPath.value()` not `searcherSettings.defaultSearchPath.flow.first()`
    - For setting values use: `searcherSettings.someSetting.value(newValue)`
- Use `FlowCombineExtensions` instead of nesting multiple combine statements.
- Prefer Kotlin standard library types over Java equivalents:
    - Use `kotlin.Uuid` instead of `java.util.UUID`
    - Use `kotlin.time.Instant` instead of `java.time.Instant`
    - Use `kotlin.time.Duration` instead of `java.time.Duration`
    - Use Kotlin collections and their extension functions
- Check if `@OptIn` annotations are actually necessary before adding them:
    - Many experimental APIs (like `ExperimentalMaterial3Api`) are already enabled project-wide via gradle compile flags (`freeCompilerArgs`)
    - Only add `@OptIn` if you get a compilation error without it

## Business Logic

- Abstract path system (`APath`, `RawPath`).
    - `APath` offers path segment infos via `segments`. Use that instead of path splitting.
- Gateway pattern for different file access methods.
    - All file I/O must go through the gateway system (`APath` + `GatewaySwitch`). Never bypass the gateway by using `java.io.File`, `DocumentFile`, or other Android/Java I/O APIs directly.
    - Before adding new methods to `FileSystemOps` or other gateway interfaces, check if existing gateway functions can accomplish the task. Only add new gateway methods when absolutely necessary or when there is significant upside (e.g., performance, seekability).
- Support for root, ADB, and shell operations.

## Type Converters and Serialization

- When creating type converters or serialization tools, consider the scope:
    - **Global types** (e.g., `Instant`, `Duration`, `Uuid`): Place converters in the `app-common` module for reuse across the entire application
    - **Workspace-specific types**: Place converters in the respective workspace module (e.g., editor-specific converters in `app-workspace-editor`)
    - This ensures proper code organization and prevents duplication

## Room Migrations

- **Policy**: Release builds have NO destructive migration fallback — a missing migration crashes loudly instead of silently wiping user data. Debug builds use `fallbackToDestructiveMigration()` for iteration convenience.
- All databases use `exportSchema = true` with schemas committed under `<module>/schemas/`.
- Each database class exposes a `MIGRATIONS: Array<Migration>` companion property. Production builders wire it via `addMigrations(*XxxDatabase.MIGRATIONS)`.
- **When bumping a `@Database` version**: add the `Migration` to the database's `MIGRATIONS` array and commit the newly exported schema JSON. Enforcement:
    - Per-database `*MigrationTest` (Robolectric + `MigrationTestHelper`) fails if any schema version is unreachable from version 1 via `MIGRATIONS`.
    - CI fails if schema JSONs change without being committed (catches entity changes without a version bump).
    - Per-database `*SchemaIdentityTest` pins the `identityHash` of every exported version, so a version bump also means adding the new version's hash to the test's expected map.
    - A hash MISMATCH on an already-exported version is fixed by a version bump plus a migration, never by updating the expected hash — the sole exception is a version that has genuinely never shipped. A missing or malformed schema asset is a broken asset, not a schema change.

## Logging

Butler uses a custom logging system (`Logging.kt`) for comprehensive debugging and monitoring.

Imports and priority levels: see `Logging.kt`.

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

Tags are automatically prefixed with "BTLR:" creating output like: `BTLR:Editor:Engine`

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

- Always use the lazy lambda form `{ "message" }`; use `e.asLog()` for exception stack traces (both shown above).
- Priorities: ERROR for exceptions, WARN for concerning conditions, INFO for milestones, DEBUG otherwise.
- ViewModels include the workspace ID in tags where applicable.
