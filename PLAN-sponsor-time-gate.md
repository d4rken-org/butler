# FOSS Sponsor: Add 10-second minimum visit time before unlock

## Context

Currently, the FOSS upgrade screen unlocks all features the instant the user taps the Sponsor button — the `FossUpgrade` is written to DataStore before the browser even opens. The user never actually needs to look at the sponsors page.

The change adds a 10-second gate: the browser opens but the upgrade isn't written. When the user returns to the app, elapsed time is checked. If < 10s, a snackbar guilt-trips them. If >= 10s, the upgrade proceeds as before. Each button tap restarts the timer.

## Changes

### 1. `app/src/foss/java/eu/darken/butler/upgrade/core/UpgradeRepoFoss.kt`

- **Remove** `launchGithubSponsorsUpgrade()` (only called from `UpgradeViewModel`)
- **Add** `openSponsorPage()` — opens browser only, no DataStore write
- **Add** `suspend fun applyUpgrade()` — writes `FossUpgrade` to DataStore

### 2. `app/src/foss/java/eu/darken/butler/upgrade/ui/UpgradeViewModel.kt`

- Inject `SavedStateHandle` to persist `sponsorOpenedAt` across process death
  - Store as `Long?` (epoch millis) since `Instant` isn't Parcelable
  - Key: `"sponsor_opened_at"`
- Add `snackbarEvent = SingleEventFlow<Int>()` for one-shot snackbar string res ID
- **Rewrite `openSponsor()`**: save `now()` millis to `SavedStateHandle`, call `upgradeRepo.openSponsorPage()`
- **Add `onAppResumed()`**: read millis from `SavedStateHandle`, if set:
  - `>= 10s` → call `applyUpgrade()`, wait for `isUpgraded`, clear handle, `navUp()`
  - `< 10s` → emit snackbar event, clear handle (so next tap restarts timer)

### 3. `app/src/foss/java/eu/darken/butler/upgrade/ui/UpgradeScreen.kt`

**Host:**
- Add `SnackbarHostState`, `rememberCoroutineScope()`
- Add `LaunchedEffect` collecting `vm.snackbarEvent` → show snackbar
- Add `LifecycleResumeEffect(Unit)` → call `vm.onAppResumed()`
- Pass `snackbarHostState` to `UpgradeScreen`

**Page:**
- Add `snackbarHostState: SnackbarHostState = remember { SnackbarHostState() }` parameter
- Add `snackbarHost = { SnackbarHost(snackbarHostState) }` to Scaffold

### 4. `app/src/foss/res/values/strings.xml`

Add:
```xml
<string name="upgrade_screen_sponsor_too_fast">Back already? Your support keeps Butler alive — please take a moment to check out the sponsors page.</string>
```

## Edge Cases

- `LifecycleResumeEffect` fires on first composition — safe because `SavedStateHandle` value is null initially
- `SavedStateHandle` survives process death — timer persists even if system kills the app while browser is open
- ViewModel survives config changes — no state lost on rotation

## Verification

1. Build: `./gradlew :app:compileFossDebugKotlin --no-daemon`
2. Manual test on device:
   - Tap Sponsor → browser opens → return immediately → snackbar shown, NOT upgraded
   - Tap Sponsor → browser opens → wait 10s+ → return → upgraded, navigates back
   - Tap Sponsor → return fast → tap again → return fast → still not upgraded (timer resets)

## Key Reference Files

- `app/src/foss/java/eu/darken/butler/upgrade/core/UpgradeRepoFoss.kt` — repo with upgrade logic
- `app/src/foss/java/eu/darken/butler/upgrade/core/FossCache.kt` — DataStore for upgrade state
- `app/src/foss/java/eu/darken/butler/upgrade/core/FossUpgrade.kt` — upgrade data model
- `app/src/foss/java/eu/darken/butler/upgrade/ui/UpgradeViewModel.kt` — ViewModel
- `app/src/foss/java/eu/darken/butler/upgrade/ui/UpgradeScreen.kt` — UI (Host/Page pattern)
- `app/src/foss/res/values/strings.xml` — FOSS-specific strings

## Existing Patterns to Reuse

- `SingleEventFlow<T>` from `app-common/.../common/flow/SingleEventFlow.kt` — for one-shot snackbar events
- `LifecycleResumeEffect` — already used in `SupportContactFormScreen.kt`
- Snackbar wiring pattern — already used in `SupportContactFormScreen.kt` (Host creates `SnackbarHostState`, passes to Page)
- `SavedStateHandle` — already injected in `RecorderViewModel` for nav args
