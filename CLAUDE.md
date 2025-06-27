# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## About Butler

Butler is an open-source Android file explorer with advanced features including root access, ADB integration, and multiple workspace support. It's built using modern Android development practices with Jetpack Compose, Kotlin Coroutines, and Hilt dependency injection.

## Development Commands

### Building the Project

```bash
# Build debug version (FOSS flavor)
./gradlew :app:compileFossDebugKotlin --no-daemon

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

### Workspace-Based Architecture

Butler uses a workspace concept similar to browser tabs with 4 main workspace types:

- **EXPLORER**: File browsing and management
- **SEARCHER**: File search functionality
- **EDITOR**: Text editing
- **TEMPLATES**: Workspace template management

### Module Structure

- `app`: Main application module
- `app-common`: Core shared utilities and base classes
- `app-common-io`: File I/O operations and abstract path system
- `app-common-root`: Root access functionality
- `app-common-adb`: Android Debug Bridge integration
- `app-common-shell`: Shell operations
- `app-common-pkgs`: Package management utilities
- `app-common-test`: Testing utilities and helpers

### Key Architectural Patterns

**MVVM with Custom ViewModel Hierarchy**:

- `ViewModel1` → `ViewModel2` → `ViewModel3` → `ViewModel4`
- `ViewModel4` adds navigation capabilities
- Uses Hilt for assisted injection

**Dependency Injection**:

- Hilt/Dagger throughout the application
- `@AndroidEntryPoint` for Activities/Fragments
- `@HiltViewModel` for ViewModels
- Modular DI setup across different modules

**UI Framework**:

- Full Jetpack Compose with Material 3
- Custom theming system (`ButlerTheme`, `ButlerColors`)
- Edge-to-edge display support

**File System Abstraction**:

- Abstract path system (`APath`, `RawPath`)
- Gateway pattern for different file access methods
- Support for root, ADB, and shell operations

## Development Guidelines

### Coding Standards

- Package by feature, not by layer
- Extract user-facing text to `strings.xml`
- Prefer adding to existing files unless creating new logical components
- Write tests for web APIs and serialized data
- No UI tests required
- Use FOSS debug flavor for local testing
- Place compose previews below the item being previewed

### Project Structure

- Single Activity architecture with Compose navigation
- Reactive programming with Kotlin Flow and StateFlow
- Centralized error handling with `ErrorEventHandler`
- DataStore-based settings with kotlinx serialization

### Build Flavors

- **FOSS**: Open source version without Google Play dependencies
- **GPLAY**: Google Play version with additional features

## Key Dependencies

- Jetpack Compose for UI
- Hilt for dependency injection
- Kotlin Coroutines & Flow for async operations
- kotlinx for JSON serialization
- Coil for image loading
- Navigation3 for navigation
- Room for database operations