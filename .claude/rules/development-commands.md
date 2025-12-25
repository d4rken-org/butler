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

## Testing

```bash
# Run all unit tests in the project
./gradlew testDebugUnitTest

# Run unit tests for a specific module
./gradlew :app-common-io:testDebugUnitTest

# Run a specific test class
./gradlew :app-common-io:testDebugUnitTest --tests "eu.darken.butler.common.files.operations.GenericPathCopyTest"

# Run a specific test method
./gradlew :app-common-io:testDebugUnitTest --tests "eu.darken.butler.common.files.operations.GenericPathCopyTest.testCopyFile"

# Run instrumented tests (on connected device/emulator)
./gradlew connectedAndroidTest
```

### Test Context Management

When running gradle test commands, use the Task tool with a sub-agent to keep verbose test output isolated from the main context:

**Default approach (preferred):**

- Use Task tool → general-purpose agent → run gradle test command
- Sub-agent should report back only:
    - Success/failure status
    - Test failures (if any) with file locations and error messages
    - Count of passed/skipped tests (without full output)

**Run gradle directly in main context only when:**

- User explicitly requests to see full test output
- Quick verification of test availability

This aligns with the "Agent instructions" principle of maintaining focused contexts and optimizes token usage.

### Compose UI Testing

Compose UI tests run on Robolectric for fast local execution without an emulator.

**Infrastructure:**

- Extend `ComposeTest` base class from `app-common-test`
- Uses `TestApplication` for fast test initialization (~10s vs ~2min)
- Wrap composables in `PreviewWrapper` for theming

**Known Limitations (Robolectric):**

- No native bitmap (`ImageBitmap()` causes NullPointerException)
- No drawing (`captureToImage()` deadlocks)
- Text measurement is inaccurate (fixed height, 1px width per char)

**Use for:** Testing component behavior, clicks, callbacks, content display
**Not for:** Visual appearance, screenshot comparison, layout pixel precision

**Example:**

```kotlin
class MyComponentTest : ComposeTest() {
    @Test
    fun `click triggers callback`() {
        var clicked = false
        composeTestRule.setContent {
            PreviewWrapper {
                MyComponent(onClick = { clicked = true })
            }
        }
        composeTestRule.onNodeWithText("Click me").performClick()
        clicked shouldBe true
    }
}
```

**Running tests:**

```bash
# Module without flavors
./gradlew :app-workspace:testDebugUnitTest --tests "*.MyComponentTest"

# Module with flavors (app-common)
./gradlew :app-common:testFossDebugUnitTest --tests "*.MyComponentTest"
```

## Debugging

### Taking Screenshots via ADB

When debugging UI issues, layout problems, or visual elements:

```bash
# Use the screenshot script (preferred method)
./.claude/scripts/screenshot.sh

# Or with a custom filename
./.claude/scripts/screenshot.sh my-ui-bug

# Manual method (if script unavailable)
mkdir -p .claude/tmp && adb shell screencap -p > .claude/tmp/screenshot.png
```

Use cases:

- Verifying UI element positioning (badges, overlays, spacing)
- Checking visual appearance of components
- Confirming layout issues before/after fixes
- Documenting visual bugs

## Fastlane Deployment

```bash
# Deploy beta version
fastlane android beta

# Deploy production version
fastlane android production
```

## Development Tooling

### Test File Structure Creator

Located in `tooling/test-files/`, this tool creates comprehensive test file structures on Android devices for testing Butler's file operations, navigation, and performance.

**Quick Usage:**

```bash
# 1. Check connected devices
adb devices -l

# 2. Push and execute script (use -s <SERIAL> for specific device)
adb push tooling/test-files/create-test-files.sh /sdcard/
adb shell "sh /sdcard/create-test-files.sh /sdcard/aButlerTests"
```

**What it creates:**

- `adirwithlargefiles/` - 8 files from 100MB to 8GB with random data (~16.5GB total)
- `adirwithmanyfiles/` - 4,000 small files (0-50KB each, ~100MB total)
- `adirwithnesteddata/` - Balanced tree structure (~1,500 folders, ~3,500 files, 10 levels deep)

**Requirements:**

- 18GB free space on device
- 15-25 minutes runtime (varies by device)

**Full documentation:** See `tooling/test-files/README.md` for detailed usage, troubleshooting, and customization options.
