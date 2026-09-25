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
module that drives the installed app with UiAutomator. It is self-instrumenting: the tests
run in their own process, so they can `pm clear`, force-stop and relaunch the app. Its sources are in
`src/main`, and its selectors look up the app's string resources by name rather than matching
English text.

```bash
ANDROID_SERIAL=emulator-5554 ./gradlew :app-e2e:connectedFossDebugAndroidTest
```

The same tests also drive the R8-minified gplay beta build. Only the gplay flavor's beta and release
builds are obfuscated, so this run can catch a missing keep rule on the paths it exercises, which the
unminified debug build hides. `setupCredentials` signs beta builds with the keystore the `STORE_*`
variables name, but only if that file exists; otherwise it falls back to the release signing
properties under `~/.config/projects/eu.darken.butler/`, if present. Check for the debug keystore
(any debug build creates it) and scope the variables to the command, so later release builds in the
same shell keep their own keys:

```bash
test -f ~/.android/debug.keystore &&
    STORE_PATH=$HOME/.android/debug.keystore STORE_PASSWORD=android \
    KEY_ALIAS=androiddebugkey KEY_PASSWORD=android \
    ANDROID_SERIAL=emulator-5554 ./gradlew :app-e2e:connectedGplayBetaAndroidTest
```

The test wipes the app under test: it starts with `pm clear eu.darken.butler`, and debug and beta builds
share the release application id. Point `ANDROID_SERIAL` only at an emulator started for the run.

`UpgradeTest` in the same module checks the upgrade path: its `beforeUpgrade` phase onboards and
opens an Explorer tab in an older build, and `afterUpgrade` expects that tab back after the current
build is installed over it. The Gradle task skips it, because only `tools/upgrade-test.sh` swaps the
APK between the phases. The script re-signs both app APKs with `~/.android/debug.keystore`, since an
in-place install needs matching keys, and refuses to run without `ANDROID_SERIAL`. An optional fourth
argument is the older build's own `:app-e2e` APK, so `beforeUpgrade` runs the steps written for that
release; without it (tags older than `UpgradeTest`), the current test APK drives the older build. CI
runs it on API 36 with gplay beta builds on both sides, starting from the nearest `v*` tag before
`HEAD`, so the saved Explorer tab must survive an upgrade between independently minified app builds.
Results land in `app-e2e/build/outputs/upgrade-test`.

```bash
(
    set -e
    test -f "$HOME/.android/debug.keystore"
    export STORE_PATH="$HOME/.android/debug.keystore" STORE_PASSWORD=android \
        KEY_ALIAS=androiddebugkey KEY_PASSWORD=android
    git worktree add --detach /tmp/upgrade-base "$(git describe --tags --abbrev=0 --match 'v*' HEAD^)"
    (cd /tmp/upgrade-base && ./gradlew :app:assembleGplayBeta)
    # Remove stale APKs before using the glob below.
    rm -rf app/build/outputs/apk/gplay/beta
    ./gradlew :app:assembleGplayBeta :app-e2e:assembleGplayBeta
    ANDROID_SERIAL=emulator-5554 tools/upgrade-test.sh \
        /tmp/upgrade-base/app/build/outputs/apk/gplay/beta/*.apk \
        app/build/outputs/apk/gplay/beta/*.apk \
        app-e2e/build/outputs/apk/gplay/beta/app-e2e-gplay-beta.apk
)
```

Reports land in `<module>/build/reports/androidTests/connected/`. In CI, the `Emulator tests`
workflow (`.github/workflows/emulator.yml`) runs them on API 30 and API 36 (the gplay beta golden
path and the upgrade test on API 36 only) and uploads the `device-tests-api-<level>` artifact even
when they fail. The workflow names each module's task
explicitly: a module that gains device tests (an `androidTest` source set, or a `com.android.test`
module like `:app-e2e`) must be added to both its build and its run step, or its tests never run in CI.

Mocking on a device uses `mockk-android`, which needs a mockk release that ships its native agent
(1.14.9 does, 1.12.4 did not).
