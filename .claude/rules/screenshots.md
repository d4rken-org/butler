---
paths: ["app/src/screenshotTest/**", "app/src/debug/**", "fastlane/**"]
---

# Play Store Screenshot Generation

Butler uses **Jetpack Compose Preview Screenshot Testing** to render Play Store screenshots. No
device or emulator is needed — everything is rendered via layoutlib.

Two axes: **8 screens × 3 form factors = 24 renders per locale**, across **68 locales**.

## Architecture

```
ScreenshotContent.kt      →  Mock UI state + pane composition per screen (src/debug/)
PlayStoreLocales.kt       →  3 annotation classes, one per form factor (device spec + locales)
PlayStoreScreenshots.kt   →  24 @PreviewTest functions (screen × form factor)
        ↓
fastlane/screenshots/locales.txt  →  the locale source of truth
        ↓
generate_screenshots.sh   →  Batch-renders to avoid the layoutlib memory leak
        ↓
copy_screenshots.sh       →  Sorts PNGs into fastlane/metadata/android/{locale}/images/
```

## Commands

```bash
# Refresh the committed English set (24 renders, 1 batch) — the usual path
./fastlane/generate_screenshots.sh --english

# Smoke test (6 locales: en-US, de-DE, ja-JP, ar, zh-CN, pt-BR)
./fastlane/generate_screenshots.sh --smoke

# Full generation (68 locales, 68 batches — slow)
./fastlane/generate_screenshots.sh

# Custom batch size (default is 1 locale = 24 renders per batch)
./fastlane/generate_screenshots.sh --batch-size 2

# Sort rendered screenshots into fastlane metadata (--clean replaces the target dirs)
./fastlane/copy_screenshots.sh --clean
```

## Form Factors

| Form factor | Device spec           | dp        | Derived layout   |
|-------------|-----------------------|-----------|------------------|
| Phone       | 1080×2400 px, 428 dpi | 404×897   | SINGLE           |
| 7" tablet   | 1200×1920 px, 320 dpi | 600×960   | DUAL_HORIZONTAL  |
| 10" tablet  | 2560×1600 px, 320 dpi | 1280×800  | TRIPLE_MAIN_LEFT |

The form factor of a screenshot comes from **the name of its `@PreviewTest` function**
(`<Screen>Phone` / `<Screen>Seven` / `<Screen>Ten`), never from the preview `name` — that stays the
plain fastlane locale directory so `copy_screenshots.sh` can read the locale out of the file name.
The three annotation classes differ only in the device spec they pin.

Rendered file name: `ExplorerHomeSeven_en-US_<hash>_0.png`.

### Why tablet shots compose panes explicitly

Screenshot content calls the page composables **directly**, bypassing `WorkspacesScreen`. The
adaptive chain (`rememberWindowSizeInfo()` → `recommendedLayout` → `WorkspaceDesign` →
`AdaptiveWorkspaceLayout`) never runs for a bare page, so a larger device spec on its own only
stretches a single pane.

`ScreenshotPaneFrame` in `ScreenshotContent.kt` closes that gap: it drives the real
`AdaptiveWorkspaceLayout` (navigation rail included) with synthetic state. Two things it must get
right:

- **Pane content is dispatched by workspace id, not by type.** `LocalWorkspacePageHosts` is keyed by
  `Workspace.Type` alone, so a type-keyed fake would render identical content in both panes when a
  screenshot shows two panes of the same type (the explorer home shots do exactly that).
- **The `selected` map is zero-based** and must cover every pane index the layout renders; a missing
  entry renders the empty-pane placeholder instead.

Pass `layout = null` to use the layout the window size recommends, or an explicit
`WorkspaceDesign.Layout` to force one (the multi-pane shots force `DUAL_HORIZONTAL` / `QUAD_GRID`).

## Locales

`fastlane/screenshots/locales.txt` is the single source of truth:

```
<android resource qualifier> <fastlane metadata directory>
```

Field 1 goes into `@Preview(locale = ...)` and selects the `values-<qualifier>` resource directory;
it is derived from `crowdin.yml` (play store `locale` mapping → crowdin code → `android_code`), so
region-coded locales carry the Android `-r` form (`pt-rBR`, not `pt-BR`). Field 2 is the
`fastlane/metadata/android/<dir>` the PNGs land in.

`generate_screenshots.sh` validates the list (two fields per row, no duplicate qualifiers, no
duplicate directories, exactly one `en-US`) **before** it rewrites any source file.

### English-only commit policy

Butler has no translations in the repo — Crowdin is the only source — so a localized run would
produce 68 copies of the same English screenshots. Only the `en-US` set is committed; the other
locales are gitignored, and a full run is meant to be done right before uploading.

Upload with `fastlane android screenshots_only` (screenshots and nothing else).

## Batch Generation

`generate_screenshots.sh` works around a layoutlib memory leak (rendered images accumulate without
being released) by:

1. Splitting the locales into batches of `--batch-size` (default 1 = 24 renders per batch).
2. Rewriting `PlayStoreLocales.kt` in place with only that batch's locales, then running Gradle with
   `--no-daemon --rerun-tasks` (without `--rerun-tasks` the update task reports up to date after the
   reference directory has been wiped and renders nothing).
3. Stopping the Gradle daemon between batches to release memory.
4. Restoring the original `PlayStoreLocales.kt` on exit, including on HUP/INT/TERM.

The image count is checked after every batch and at the end; a mismatch fails the run.

Runs serialize through a lock file in `$TMPDIR` — the rewritten source, the reference output
directory and `gradlew --stop` are all shared state, so concurrent runs would corrupt each other.

The annotation file is generated **in place** rather than into a generated source root: the
checked-in `PlayStoreLocales.kt` declares the same annotation classes, so a second generated copy
would be a duplicate declaration.

## Screenshot Copy

`copy_screenshots.sh` runs in two phases and fails closed:

1. **Parse & validate** every source into an in-memory manifest — unknown function names, duplicate
   destinations, unknown form factors, wrong pixel dimensions and locales without exactly 8 images
   per form factor are all fatal. On any violation nothing is copied.
2. **Stage & commit** — all copies are staged into a temp dir first; only once staging fully
   succeeds are the destination directories replaced. `--clean` happens only in this phase and only
   for the locales in this run.

## Output Locations

- **Gradle reference images**: `app/src/screenshotTestGplayDebug/reference/.../PlayStoreScreenshotsKt/*.png`
- **Fastlane metadata**: `fastlane/metadata/android/{locale}/images/{phone,sevenInch,tenInch}Screenshots/`

## Modifying Screenshots

### Adding a new screen

Everything below has to stay in sync:

1. `ScreenshotContent.kt`: a `<Screen>Body` composable with mock data, a `ScreenshotPane` for it if
   it should be usable as a pane, and a `<Screen>Content(formFactor)` wrapper that adds
   `PreviewWrapper` exactly once. Never call another `*Content()` from inside a pane — that would
   nest a whole `PreviewWrapper` per pane.
2. `ScreenshotContent.kt`: three IDE `@Preview` functions, one per device spec.
3. `PlayStoreScreenshots.kt`: three `@PreviewTest` functions —
   `<Screen>Phone` / `<Screen>Seven` / `<Screen>Ten`. **Function names must not contain
   underscores**: the copy script splits the rendered file name at the first underscore.
4. `copy_screenshots.sh`: three `SCREEN_MAP` entries, `"<form factor>:<order>_<label>"`.
5. `copy_screenshots.sh`: bump `SCREENS_PER_FORM_FACTOR`.
6. `generate_screenshots.sh`: bump `RENDERS_PER_LOCALE` (screens × 3).
7. This file: update the counts at the top.

Removing a screen is the same list in reverse.

### Changing mock data

Edit `ScreenshotContent.kt`. The bodies use the per-module preview providers (`MockDataProvider`,
`SearcherMockDataProvider`, `AppsMockDataProvider`, `TemplatesMockDataProvider`).

Renders must be **deterministic** — anything random or date-dependent has to be gated on
`LocalInspectionMode.current` (see the templates picker's slogan).

### Adding/removing locales

Edit `fastlane/screenshots/locales.txt`. Do not hand-edit `PlayStoreLocales.kt` for a run; the
generator rewrites it and restores it afterwards.

## ADB Screenshots (Debugging)

For quick device screenshots during debugging (not Play Store generation):

```bash
./.claude/skills/screenshot/screenshot.sh [filename]
```

Output: `.claude/tmp/{filename}.png`. Use for verifying layouts, documenting bugs, before/after
comparisons.
