# Testing Guidelines

## Test Commands

Standard Gradle invocations (`testDebugUnitTest`, `--tests "<fqcn>"`, `connectedAndroidTest`). Flavored modules need the flavor in the task name — see the Compose section below.

### Context Management

When running test commands, use the Task tool with a sub-agent to keep verbose output isolated from the main context:

**Default approach (preferred):**

- Use Task tool → `devtools:build-runner` agent → run gradle test command
- Sub-agent should report back only:
    - Success/failure status
    - Test failures (if any) with file locations and error messages
    - Count of passed/skipped tests (without full output)

**Run gradle directly in main context only when:**

- User explicitly requests to see full test output
- Quick verification of test availability

## What to Test

- Write tests for web APIs and serialized data
- Use FOSS debug flavor for local testing

## Compose UI Testing

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

**Running Compose tests:**

```bash
# Module without flavors
./gradlew :app-workspace:testDebugUnitTest --tests "*.MyComponentTest"

# Module with flavors (app-common)
./gradlew :app-common:testFossDebugUnitTest --tests "*.MyComponentTest"
```
