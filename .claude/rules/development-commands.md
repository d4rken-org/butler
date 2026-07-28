# Development Commands

## Prerequisites

- **JDK 21 (Temurin), at least 21.0.9.** CI (`.github/actions/common-setup/action.yml`) runs Temurin
  21, and the repo-root `.sdkmanrc` pins `java=21.0.11-tem` for local use (`sdk env install`).
  Temurin 21.0.5-21.0.8 must be avoided: they carry a C2 compiler SIGSEGV (JDK-8358534) that kills
  Gradle test workers mid-run.
- The build sets no Gradle Java toolchain, so whatever JDK runs Gradle is what compiles the project.
  `jvmTarget`/`sourceCompatibility` stay at 17 — that is the bytecode target, not the build JDK.

## Building the Project

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

### Build & Test Context Management

Run gradle build and test commands via the `devtools:build-runner` agent (Task tool) to keep verbose output out of the main context. The sub-agent reports back only: success/failure status, compilation/test errors with file locations, and warning/test counts. Run gradle directly only when the user asks for full output, or for quick verification of available tasks or tests (`./gradlew tasks`).

## Code Quality

```bash
# Run lint checks (used in CI)
./gradlew lintVitalFossRelease

# Run lint checks for all variants
./gradlew lint
```

## Play Store Screenshots

Localized Play Store screenshots are generated via Compose Preview Screenshot Testing — pipeline, commands, and batching details in `.claude/rules/screenshots.md`.

## Debugging

### Taking Screenshots via ADB

When debugging UI issues, layout problems, or visual elements:

```bash
# Use the screenshot script (preferred method)
./.claude/skills/screenshot/screenshot.sh

# Or with a custom filename
./.claude/skills/screenshot/screenshot.sh my-ui-bug
```

Use cases:

- Verifying UI element positioning (badges, overlays, spacing)
- Checking visual appearance of components
- Confirming layout issues before/after fixes
- Documenting visual bugs

## Fastlane Deployment

The Gemfile lives in `fastlane/`, so invoke fastlane with `BUNDLE_GEMFILE` set or from the `fastlane/` directory.

```bash
# Deploy beta version
BUNDLE_GEMFILE=fastlane/Gemfile bundle exec fastlane android beta

# Deploy production version
BUNDLE_GEMFILE=fastlane/Gemfile bundle exec fastlane android production
```
