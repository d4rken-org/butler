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

# Build specific modules (every module carries the foss/gplay dimension)
./gradlew :app-workspace:compileFossDebugKotlin --no-daemon

# Build release version
./gradlew :app:bundleFossRelease
```

## Validating a Constructor Change

A production compile does not compile unit test sources. Adding a parameter to an injected
constructor therefore breaks every test that builds that class with named arguments while
`compileFossDebugKotlin` still succeeds, and the failure only surfaces once tests are run.
Compile the test sources too:

```bash
# Compile unit test sources without running them
./gradlew :app:compileFossDebugUnitTestKotlin
```

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

## Fastlane Deployment

The Gemfile lives in `fastlane/`, so invoke fastlane with `BUNDLE_GEMFILE` set or from the `fastlane/` directory.

```bash
# Deploy beta version
BUNDLE_GEMFILE=fastlane/Gemfile bundle exec fastlane android beta

# Deploy production version
BUNDLE_GEMFILE=fastlane/Gemfile bundle exec fastlane android production
```
