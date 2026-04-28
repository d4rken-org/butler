# Development Commands

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

### Build Context Management

When running gradle build commands, use the Task tool with a sub-agent to keep verbose build output isolated from the main context:

**Default approach (preferred):**

- Use Task tool → general-purpose agent → run gradle command
- Sub-agent should report back only:
    - Success/failure status
    - Compilation errors (if any) with file locations
    - Count of warnings (without full output)

**Run gradle directly in main context only when:**

- User explicitly requests to see full build output
- Quick verification of available gradle tasks (`./gradlew tasks`)

This aligns with the "Agent instructions" principle of maintaining focused contexts and optimizes token usage.

## Code Quality

```bash
# Run lint checks (used in CI)
./gradlew lintVitalFossRelease

# Run lint checks for all variants
./gradlew lint
```

## Play Store Screenshots

Generate localized screenshots using Compose Preview Screenshot Testing. See `.claude/rules/screenshots.md` for full details.

```bash
# Smoke test (6 locales, fast iteration)
./fastlane/generate_screenshots.sh --smoke

# Full generation (76 locales)
./fastlane/generate_screenshots.sh

# Copy to fastlane metadata directories
./fastlane/copy_screenshots.sh

# Direct Gradle (no batching)
./gradlew :app:updateGplayDebugScreenshotTest --no-daemon
```

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
