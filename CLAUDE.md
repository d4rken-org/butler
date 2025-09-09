# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## About Butler

Butler is an open-source Android file explorer with advanced features including root access, ADB integration, and multiple workspace support. It's built using modern Android development practices with Jetpack Compose, Kotlin Coroutines, and Hilt dependency injection.

Butler uses a workspace concept similar to browser tabs with 4 main workspace types:

- **EXPLORER**: File browsing and management
- **SEARCHER**: File search functionality
- **EDITOR**: Text editing
- **TEMPLATES**: Workspace template management

## Development Commands

### Building the Project

```bash
# Build debug version (FOSS flavor) - main app
./gradlew :app:compileFossDebugKotlin --no-daemon

# Build specific modules (use compileDebugKotlin, not compileFossDebugKotlin for modules)
./gradlew :app-workspace:compileDebugKotlin --no-daemon
./gradlew :app-workspace-explorer:compileDebugKotlin --no-daemon
./gradlew :app-workspace-searcher:compileDebugKotlin --no-daemon
./gradlew :app-workspace-editor:compileDebugKotlin --no-daemon
./gradlew :app-workspace-templates:compileDebugKotlin --no-daemon

# Build release version
./gradlew :app:bundleFossRelease

# Clean build
./gradlew clean
```

### Testing

```bash
# Run unit tests
./gradlew test

# Run instrumented tests
./gradlew connectedAndroidTest
```

### Fastlane Deployment

```bash
# Deploy beta version
fastlane android beta

# Deploy production version
fastlane android production
```

## Architecture Overview

### Build Flavors

- **FOSS**: Open source version without Google Play dependencies.
- **GPLAY**: Google Play version with additional features.

### Module Structure

#### Core Application
- `app`: Main application module with entry point, flavor-specific implementations, and setup flow.

#### Foundation Modules
- `app-common`: Core shared utilities, base architecture components, custom ViewModel hierarchy, theming system.
- `app-common-test`: Testing utilities, helpers, and base test classes for all modules.

#### Platform Integration Modules  
- `app-common-io`: File I/O operations, abstract path system (APath), gateway pattern for file access methods.
- `app-common-root`: Root access functionality and root-based file operations.
- `app-common-adb`: Android Debug Bridge integration via Shizuku API.
- `app-common-shell`: Shell operations and reactive command execution with FlowShell.
- `app-common-pkgs`: Package management utilities and package event handling.

#### Workspace Modules
- `app-workspace`: Core workspace framework, base classes, and tab-like workspace management.
- `app-workspace-explorer`: File browsing workspace with navigation, file operations, sorting/filtering.
- `app-workspace-searcher`: File search workspace with search engine, filters, and result caching.
- `app-workspace-editor`: Text editing workspace with chunked buffer system for large files.
- `app-workspace-templates`: Workspace template management and type switching.

## Coding Standards

- Package by feature, not by layer.
- All user facing strings should be extract to `values/strings.xml` and translated for all other languages too.
- Prefer adding to existing files unless creating new logical components.
- Write tests for web APIs and serialized data.
- No UI tests required.
- Use FOSS debug flavor for local testing.
- Place compose previews below the item being previewed.
- Don't add code comments for obvious code.
- Write minimalistic and concise code (omit comments).
- Prefer flow based solutions.
- Prefer reactive programming.
- When using `if` that is not single-line, always use brackets.
- Always add trailing commas.
- In `@Composable` functions, the parameter `modifier: Modifier = Modifier,` should be the first parameter.

## Agent instructions

- Reminder: Our core principle is to maintain focused contexts for both yourself (the orchestrator/main agent) and each sub-agent. Therefore, please use the Task tool to delegate suitable tasks to sub-agents to improve task efficiency and optimize token usage.
- Be critical.
- Challenge suggestions.

## Development Guidelines

### General

- Single Activity architecture with Compose Navigation3.
- Reactive programming with Kotlin Flow and StateFlow.
- Centralized error handling with `ErrorEventHandler`.
- DataStore-based settings with kotlinx serialization.
- Jetpack Compose for UI.
- Hilt for dependency injection.
- Kotlin Coroutines & Flow for async operations.
- KotlinX for JSON serialization.
- Coil for image loading.
- Room for database operations.
- Use `FlowCombineExtensions` instead of nesting multiple combine statements.
- We don't need `@OptIn(ExperimentalMaterial3Api:class)` as we have this enabled project wide via `freeCompilerArgs`.

#### Dependency Injection

- Hilt/Dagger throughout the application.
- `@AndroidEntryPoint` for Activities/Fragments.
- `@HiltViewModel` for ViewModels.
- Modular DI setup across different modules.

### User Interface

- Full Jetpack Compose with Material 3.****
- Custom theming system (`ButlerTheme`, `ButlerColors`).
- Edge-to-edge display support.
- Use icons out of the `androidx.compose.material.icons.twotone` package where possible.
- When creating compose previews, use the `@Preview2` annotation, and wrap the UI element in a `PreviewWrapper`.

#### Localization

- All user-facing texts need to be extracted to a `strings.xml` resources file to be localizable.
- Composables should access strings by `stringResource(id = R.string.my_string)`.
- Backend classes (those in the `core`) packages and other non-composables should use `CAString` to provide localized strings.
  - `R.string.xxx.toCaString()`
  - `R.string.xxx.toCaString("Argument")`
  - `caString { getString(R.plurals.xxx, count, count) }`
- Localized strings with multiple arguments should use ordered placeholders (i.e. `%1$s is %2$d`).
- Use ellipsis characters (`…`) instead of 3 manual dots (`...`).
- Use the `strings.xml` file that belongs to respective feature module.
- General texts that are used through-out multiple modules should be placed in the `strings.xml` file of the `app-common` module.
- Before creating a new entry, check if `strings.xml` file in the `app-common` module already contains a general version.
- String IDs should be prefixed with their respective module name. Re-used strings should be prefixed with `general` or `common`.
- Where possible string IDs should not contain implementation details.
  - Postfix with `_action` instead of prefixing with `button_`.
  - Instead of `module_screen_button_open` it should be `module_screen_open_action`

#### MVVM with Custom ViewModel Hierarchy

- `ViewModel1` → `ViewModel2` → `ViewModel3` → `ViewModel4`.
- `ViewModel4` adds navigation capabilities.
- Uses Hilt for assisted injection.

### Business Logic

#### General

- Abstract path system (`APath`, `RawPath`).
  - `APath` offers path segment infos via `segments`. Use that instead of path splitting.
- Gateway pattern for different file access methods.
- Support for root, ADB, and shell operations.

### Logging

Butler uses a custom logging system (`Logging.kt`) for comprehensive debugging and monitoring.

#### Required Imports

```kotlin
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
```

#### Priority Levels

- **VERBOSE (2)**: Most detailed logging for deep debugging
- **DEBUG (3)**: General debugging information (default priority)
- **INFO (4)**: Important informational messages and milestones
- **WARN (5)**: Warning conditions that need attention
- **ERROR (6)**: Error conditions and exceptions
- **ASSERT (7)**: Critical assertions and "WTF" moments

#### Tag Conventions

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

#### Usage Patterns

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

#### Best Practices

- Always use lazy evaluation with lambda: `{ "message" }` for performance
- Use `e.asLog()` extension for exception logging to get full stack traces
- Use appropriate priority levels: ERROR for exceptions, WARN for concerning conditions, INFO for milestones, DEBUG for general logging
- Follow hierarchical tag naming for consistent categorization
- ViewModels should include workspace ID in tags when applicable
- Keep log messages concise but descriptive