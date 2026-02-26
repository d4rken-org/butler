# Play Store Screenshot Generation

Butler uses **Jetpack Compose Preview Screenshot Testing** to generate localized Play Store screenshots across 76 languages. No device or emulator needed — screenshots are rendered via layoutlib.

## Architecture

```
PlayStoreScreenshots.kt   →  Composable test functions (6 screens × 76 locales)
PlayStoreLocales.kt       →  @Preview annotations defining locale + device spec
ScreenshotContent.kt      →  Mock UI state for each screen (in src/debug/)
        ↓
generate_screenshots.sh   →  Batch-renders to avoid layoutlib memory leak
        ↓
copy_screenshots.sh       →  Organizes PNGs into fastlane/metadata/android/{locale}/
```

## Key Files

| File | Purpose |
|------|---------|
| `app/src/screenshotTest/kotlin/.../PlayStoreScreenshots.kt` | 6 `@PreviewTest` composables (one per screen) |
| `app/src/screenshotTest/kotlin/.../PlayStoreLocales.kt` | `@PlayStoreLocales` (76 locales) and `@PlayStoreLocalesSmoke` (6 locales) annotations, device spec |
| `app/src/debug/java/.../ScreenshotContent.kt` | Mock content composables with sample data |
| `fastlane/generate_screenshots.sh` | Batch generation script |
| `fastlane/copy_screenshots.sh` | Copies rendered PNGs to fastlane metadata dirs |

## Screens Captured

| # | Function | Output Filename |
|---|----------|-----------------|
| 1 | `ExplorerHome()` | `1_explorer_home.png` |
| 2 | `ExplorerDirectory()` | `2_explorer_directory.png` |
| 3 | `SearcherResults()` | `3_searcher_results.png` |
| 4 | `EditorView()` | `4_editor.png` |
| 5 | `AppsManager()` | `5_apps.png` |
| 6 | `WorkspaceManager()` | `6_workspace_manager.png` |

## Commands

```bash
# Full generation (76 locales, ~19 batches — slow, use for final output)
./fastlane/generate_screenshots.sh

# Smoke test (6 locales: en, de, ja, ar, zh-CN, pt-BR — fast iteration)
./fastlane/generate_screenshots.sh --smoke

# Custom batch size (default is 4 locales per batch)
./fastlane/generate_screenshots.sh --batch-size 10

# Copy rendered screenshots to fastlane metadata directories
./fastlane/copy_screenshots.sh

# Copy with clean (removes existing screenshots first)
./fastlane/copy_screenshots.sh --clean

# Direct Gradle (single run, no batching — may OOM with many locales)
./gradlew :app:updateGplayDebugScreenshotTest --no-daemon
```

## Output Locations

- **Gradle reference images**: `app/src/screenshotTestGplayDebug/reference/.../PlayStoreScreenshotsKt/*.png`
- **Gradle build output**: `app/build/outputs/screenshotTest-results/preview/debug/gplay/rendered/.../PlayStoreScreenshotsKt/`
- **Fastlane metadata**: `fastlane/metadata/android/{locale}/images/phoneScreenshots/`

## How It Works

### Locale System

`@PlayStoreLocales` generates 76 `@Preview` entries. Each sets `locale` (Android code) and `name` (Play Store directory name). Device spec is fixed at 822×1828px, 320dpi (Pixel phone dimensions).

`@PlayStoreLocalesSmoke` is a 6-locale subset for quick iteration covering LTR, RTL, CJK, and Latin scripts.

### Batch Generation

`generate_screenshots.sh` works around a layoutlib memory leak by:
1. Splitting 76 locales into batches (default 4 locales = 24 renders per batch)
2. For each batch: rewriting `PlayStoreLocales.kt` with only the batch locales, running Gradle, collecting PNGs
3. Stopping the Gradle daemon between batches to release memory
4. Restoring the original `PlayStoreLocales.kt` when done

### Screenshot Copy

`copy_screenshots.sh` parses the generated filenames (format: `{Function}_{locale}_{hash}_{index}.png`), maps function names to numbered screen labels, and copies to `fastlane/metadata/android/{locale}/images/phoneScreenshots/`.

## Modifying Screenshots

### Adding a new screen

1. Add a content composable in `ScreenshotContent.kt` with mock data
2. Add a `@PreviewTest @PlayStoreLocales` function in `PlayStoreScreenshots.kt`
3. Add the function-to-filename mapping in `copy_screenshots.sh` (`SCREEN_MAP`)

### Changing mock data

Edit `ScreenshotContent.kt`. The content composables use `PreviewWrapper` for theming and mock data providers from each workspace module.

### Adding/removing locales

Edit `PlayStoreLocales.kt`. Each `@Preview` entry needs `locale` (Android code) and `name` (Play Store directory name, used in filenames and fastlane paths).

## Gradle Plugin

```kotlin
// app/build.gradle.kts
plugins {
    id("com.android.compose.screenshot") version "0.0.1-alpha13"
}

dependencies {
    "screenshotTestImplementation"(platform("androidx.compose:compose-bom:..."))
    "screenshotTestImplementation"("com.android.tools.screenshot:screenshot-validation-api:0.0.1-alpha13")
    "screenshotTestImplementation"("androidx.compose.ui:ui-tooling")
}
```

## ADB Screenshots (Debugging)

For quick device screenshots during debugging (not Play Store generation):

```bash
./.claude/skills/screenshot/screenshot.sh [filename]
```

Output: `.claude/tmp/{filename}.png`. Use for verifying layouts, documenting bugs, before/after comparisons.
