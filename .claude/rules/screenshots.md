---
paths: ["app/src/screenshotTest/**", "app/src/debug/**", "fastlane/**"]
---

# Play Store Screenshot Generation

Butler uses **Jetpack Compose Preview Screenshot Testing** to render Play Store screenshots. No
device or emulator is needed — everything is rendered via layoutlib.

Two axes: **8 screens × 3 form factors**, but not a full grid — the phone set drops the explorer
home shot, so it is **7 phone + 8 seven-inch + 8 ten-inch = 23 renders per locale**, across
**68 locales**.

## Architecture

```
ScreenshotContent.kt      →  Mock UI state + pane composition per screen (src/debug/)
ScreenshotPreviewImages.kt→  Stand-in file/app images for the layoutlib render (src/debug/)
ScreenshotSystemBars.kt   →  Synthetic status/navigation bars + the inset override (src/debug/)
PlayStoreLocales.kt       →  3 annotation classes, one per form factor (device spec + locales)
PlayStoreScreenshots.kt   →  23 @PreviewTest functions (screen × form factor)
        ↓
fastlane/screenshots/locales.txt  →  the locale source of truth
        ↓
generate_screenshots.sh   →  Batch-renders to avoid the layoutlib memory leak
        ↓
copy_screenshots.sh       →  Sorts PNGs into fastlane/metadata/android/{locale}/images/
```

## Commands

```bash
# Refresh the committed English set (23 renders, 1 batch) — the usual path
./fastlane/generate_screenshots.sh --english

# Smoke test (6 locales: en-US, de-DE, ja-JP, ar, zh-CN, pt-BR)
./fastlane/generate_screenshots.sh --smoke

# Full generation (68 locales, 68 batches — slow)
./fastlane/generate_screenshots.sh

# Custom batch size (default is 1 locale = 23 renders per batch)
./fastlane/generate_screenshots.sh --batch-size 2

# Sort rendered screenshots into fastlane metadata (--clean replaces the target dirs)
./fastlane/copy_screenshots.sh --clean
```

## Form Factors

| Form factor | Device spec           | dp        | Derived layout   |
|-------------|-----------------------|-----------|------------------|
| Phone       | 1440×2560 px, 560 dpi | 411×731   | SINGLE           |
| 7" tablet   | 1080×1920 px, 288 dpi | 600×1066  | DUAL_HORIZONTAL  |
| 10" tablet  | 2560×1440 px, 320 dpi | 1280×720  | TRIPLE_MAIN_LEFT |

Every spec is exactly 16:9. Play rejects a screenshot whose long side exceeds twice its short side,
and only 9:16 portrait / 16:9 landscape shots are eligible for the promotional surfaces — the old
1080×2400 phone spec was 9:20 and satisfied neither.

**The dpi is not cosmetic.** It sets the dp size, the dp size picks the layout through
`WindowSizeInfo.recommendedPaneCount`, and a pane index the layout renders but `ScreenshotPaneFrame`
has no `selected` entry for falls back to the empty-pane placeholder. The 7" spec is 288 dpi rather
than a standard bucket for exactly this reason: 1080×1920 at 320 dpi is 540 dp wide, under the
600 dp breakpoint, which silently collapses both tablet panes to `SINGLE`. Redo that arithmetic
before changing any spec.

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

`railExtras` adds `Workspace.Info`s to the rail without giving them a pane — the real rail lists
every open tab, not only the visible ones, and a workspace with no `selected` entry renders as an
idle rail item. Their ids must not collide with any pane id.

## Shot Order

The phone and tablet sets are different lists. The number is the fastlane filename prefix, which is
the order Play shows them in.

| # | Phone (7)            | 7" and 10" tablet (8) |
|---|----------------------|-----------------------|
| 1 | multi_pane           | explorer_directory    |
| 2 | explorer_directory   | explorer_home         |
| 3 | searcher_results     | searcher_results      |
| 4 | editor *(dark)*      | editor *(dark)*       |
| 5 | apps                 | apps                  |
| 6 | workspace_manager *(dark)* | workspace_manager *(dark)* |
| 7 | templates            | multi_pane            |
| 8 | —                    | templates             |

The phone set leads with the multi-pane shot because that is what distinguishes Butler in a search
result, and drops the explorer home shot to stay within eight. The quick-create dropdown overlay
rides on the phone templates shot, which is the only phone shot left that is about creating tabs.

## System Bars and Insets

layoutlib **ignores `showSystemUi` in a screenshot-test render** — verified: neither a
`navigation=`/`cutout=` device spec nor a `parent=` device makes it paint the bars — and it reports
every window inset as zero. Left alone, panes start at y=0 and draw where the status bar belongs.

`ScreenshotSystemBars` (`ScreenshotSystemBars.kt`) fixes both halves: it overlays a synthetic status
bar and gesture handle, and provides `LocalSystemBarInsetsOverride` with the matching values.
`ScreenshotPreviewWrapper` applies it once per render.

Drawing our own is also what Play asks for — the asset guidance wants a clean notification bar with
no carrier text, no notifications and battery/wifi/signal shown full — and it keeps all 68 locales
identical. The clock is a hardcoded `12:30` for the same reason; the `Row` mirrors itself under RTL,
as a real status bar does.

`LocalSystemBarInsetsOverride` lives in `app-common` (`DisplayCutoutAvoidance.kt`) because its
consumers span layers, and it is null everywhere outside these renders — production reads the same
window insets it always did:

| Consumer | What it insets |
|----------|----------------|
| `systemBarsWithOptionalCutout()` | the navigation rail (`WorkspaceNavigationRail.kt`) |
| `rawPaneInsets()` (`WorkspacePaneInsets.kt`) | pane chrome |
| `FloatingBarStackState` | the floating bar stacks |

The bars are an **overlay, not padding**: page content is meant to scroll under them, as it does
edge-to-edge on a device. Content with no inset consumer of its own needs
`Modifier.screenshotSystemBarPadding()` instead — the workspace manager is the case that matters,
because it insets itself through a `Scaffold` and a `Scaffold` reads `WindowInsets.systemBars`
directly, which the override cannot reach.

### Single-frame collection

A single-frame render never runs a flow collection, so a page that takes `Flow` parameters must
unwrap `StateFlow` for its initial value or the bar it drives stays permanently hidden:

```kotlin
val state by source.collectAsState(initial = (source as? StateFlow)?.value)
```

`ExplorerWorkspacePage` does this for its main, operations and clipboard sources. `initial = null`
is why the operations bar rendered blank before.

## Images

Coil resolves nothing under layoutlib: its fetchers need a gateway, a package manager and disk
access. Without help, every file row renders iconless and every app row falls back to the grey
placeholder.

`ScreenshotPreviewWrapper` (`ScreenshotPreviewImages.kt`) is `PreviewWrapper` plus
`LocalAsyncImagePreviewHandler`, and it is what the eight `*Content(formFactor)` wrappers use.
`ScreenshotImagePreviewHandler` answers every Coil request from the request data alone:

| `request.data`                      | Result                                                    |
|-------------------------------------|-----------------------------------------------------------|
| `APathLookup` (image/video by name) | synthetic thumbnail, hue derived from the name            |
| `APathLookup` (`.pdf`)              | synthetic first-page render, like `PdfPreviewGenerator`   |
| `APathLookup` (anything else)       | the app's own type drawable, rasterized with a baked tint |
| `Installed`                         | synthetic tile with the package's initial                 |
| everything else                     | `AsyncImagePreviewHandler.Default`                        |

Four things it must keep doing:

- **Every result reports `DataSource.DISK`.** `TintedAsyncImage` tints only `DataSource.MEMORY`, but
  it cannot do the tinting here: it derives `shouldTint` from the painter state it observes at first
  composition — `State.Empty`, the request is not answered yet — and layoutlib never recomposes it
  to pick the answer up. Type icons therefore arrive pre-tinted: the wrapper reads
  `MaterialTheme.colorScheme.onSurfaceVariant`, and the handler bakes it into the rasterized icon.
  `MEMORY` would only risk the tint being applied twice.
- **The tint travels in the handler instance, not in a singleton.** The wrapper `remember`s one
  handler per tint. Renders can overlap, so a mutable shared handler would leak one render's theme
  colour into another's.
- **Bitmap sizes are fixed.** Resolving the request's size resolver can suspend forever — a
  single-frame render has no layout pass to feed it.
- **The unknown branch delegates.** The handler wraps all eight screenshots and sees every request,
  so a silent else branch would regress unrelated renders.

The type-icon set is what production actually resolves to, no more: there is no PDF, code or text
drawable in the repo, and `PathPreviewFetcher.fallbackIcon` genuinely falls back to `ic_file` for
them. Do not invent glyphs the app never draws — where production renders content instead of an
icon (photos, videos, PDFs), the handler renders a stand-in for that content.

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

1. Splitting the locales into batches of `--batch-size` (default 1 = 23 renders per batch).
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
   destinations, unknown form factors, wrong pixel dimensions and locales that miss their
   per-form-factor count (`SCREENS_PER_FORM_FACTOR`: phone 7, seven 8, ten 8) are all fatal. On any
   violation nothing is copied. `SCREEN_SIZE_OVERRIDE` lets a single shot render at a size other
   than its form factor's default; it is empty today.
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
   `ScreenshotPreviewWrapper` exactly once. Never call another `*Content()` from inside a pane —
   that would nest a whole wrapper per pane.
2. `ScreenshotContent.kt`: three IDE `@Preview` functions, one per device spec.
3. `PlayStoreScreenshots.kt`: a `@PreviewTest` function per form factor the screen appears on —
   `<Screen>Phone` / `<Screen>Seven` / `<Screen>Ten`. **Function names must not contain
   underscores**: the copy script splits the rendered file name at the first underscore. A form
   factor a screen does not appear on gets a `noVariant(formFactor)` branch in `ScreenshotContent.kt`
   rather than a silently rendered extra.
4. `copy_screenshots.sh`: one `SCREEN_MAP` entry per shot, `"<form factor>:<order>_<label>"`.
5. `copy_screenshots.sh`: bump the affected entries in `SCREENS_PER_FORM_FACTOR`.
6. `generate_screenshots.sh`: bump `RENDERS_PER_LOCALE` (the sum across form factors).
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
