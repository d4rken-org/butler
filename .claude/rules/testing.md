---
paths: ["**/src/test*/**", "**/src/androidTest/**", "**/*Test.kt", "app-common-test/**"]
---

# Testing Guidelines

## Test Commands

Standard Gradle invocations (`testFossDebugUnitTest`, `--tests "<fqcn>"`, `connectedAndroidTest`). Every module carries the `version` flavor dimension, so unit test tasks always include the flavor — there is no flavor-free `testDebugUnitTest`.

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
./gradlew :app-workspace:testFossDebugUnitTest --tests "*.MyComponentTest"

# The gplay side of the same module
./gradlew :app-workspace:testGplayDebugUnitTest --tests "*.MyComponentTest"
```
