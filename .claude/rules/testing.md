---
paths: ["**/src/test*/**", "**/src/androidTest/**", "**/*Test.kt", "app-common-test/**"]
---

# Testing Guidelines

## Test Commands

Standard Gradle invocations (`testFossDebugUnitTest`, `--tests "<fqcn>"`; device tests: see Device Tests below). Every module carries the `version` flavor dimension, so unit test tasks always include the flavor — there is no flavor-free `testDebugUnitTest`.

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

Robolectric runs on the build machine's filesystem and fakes system services, so anything that
depends on real Android behaviour needs a device test under `<module>/src/androidTest` (JUnit4,
`@RunWith(AndroidJUnit4::class)`): storage semantics (case-insensitive names on `/storage/emulated`,
refused symlinks, cross-filesystem moves between app storage and shared storage), the errno a
real kernel or FUSE returns, `ParcelFileDescriptor`s crossing a provider, and system providers
such as MediaStore. Everything else stays a host test.

`DeviceStorageRule` (`app-common-io`) gives each test a fresh shared-storage root under `Download`
(a FUSE mount on API 30+) and a private root under `filesDir`, grants all-files access through
`appops`, skips the test below API 30, and deletes both roots afterwards.

`ANDROID_SERIAL` is mandatory: without it `connectedAndroidTest` installs and runs on every attached
device.

```bash
ANDROID_SERIAL=emulator-5554 ./gradlew :app-common-io:connectedFossDebugAndroidTest
```

The golden path (fresh install, onboarding, first screen) lives in `:app-e2e`, a `com.android.test`
module that drives the installed FOSS debug app with UiAutomator. It is self-instrumenting: the tests
run in their own process, so they can `pm clear`, force-stop and relaunch the app. Its sources are in
`src/main`, and its selectors look up the app's string resources by name rather than matching
English text.

```bash
ANDROID_SERIAL=emulator-5554 ./gradlew :app-e2e:connectedFossDebugAndroidTest
```

The test wipes the app under test: it starts with `pm clear eu.darken.butler`, and debug builds share
the release application id. Point `ANDROID_SERIAL` only at an emulator started for the run.

Reports land in `<module>/build/reports/androidTests/connected/`. In CI, the `Emulator tests`
workflow (`.github/workflows/emulator.yml`) runs them on API 30 and API 36 and uploads the
`device-tests-api-<level>` artifact even when they fail. The workflow names each module's task
explicitly: a module that gains device tests (an `androidTest` source set, or a `com.android.test`
module like `:app-e2e`) must be added to both its build and its run step, or its tests never run in CI.

Mocking on a device uses `mockk-android`, which needs a mockk release that ships its native agent
(1.14.9 does, 1.12.4 did not).
