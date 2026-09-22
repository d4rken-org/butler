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
- Text measurement is inaccurate (line height does not follow the text style, 1px width per char).
  Annotate the class with `@GraphicsMode(GraphicsMode.Mode.NATIVE)` when an assertion needs real
  font metrics.

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

## Device Tests

Device tests live in `app-common-io/src/androidTest` and run as JUnit4 tests
(`@RunWith(AndroidJUnit4::class)`). Robolectric runs on the build machine's filesystem, so behaviour
that only exists on Android storage (case-insensitive names, refused symlinks, cross-filesystem moves
between app storage and shared storage) needs a real device or emulator.

`DeviceStorageRule` gives each test a fresh shared-storage root under `Download` (a FUSE mount on
API 30+) and a private root under `filesDir`, grants all-files access through `appops`, skips the
test below API 30, and deletes both roots afterwards.

`ANDROID_SERIAL` is mandatory: without it `connectedAndroidTest` installs and runs on every attached
device.

```bash
ANDROID_SERIAL=emulator-5554 ./gradlew :app-common-io:connectedFossDebugAndroidTest
```

Reports land in `app-common-io/build/reports/androidTests/connected/`. In CI, the `Emulator tests`
workflow (`.github/workflows/emulator.yml`) runs them on API 30 and API 36 and uploads the
`device-tests-api-<level>` artifact even when they fail.

Mocking on a device uses `mockk-android`, which needs a mockk release that ships its native agent
(1.14.9 does, 1.12.4 did not).
