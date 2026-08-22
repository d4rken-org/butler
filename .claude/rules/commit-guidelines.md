# Commit & Pull Request Guidelines

## Format

```
<module>: <user-friendly title>

<detailed technical description>

<issue references>
```

## Module Prefixes

- **Explorer**: File browsing, navigation, file operations
- **Searcher**: File search functionality
- **Editor**: Text editing
- **Viewer**: File preview and viewing
- **Apps**: App management workspace
- **Templates**: Workspace template management
- **History**: Operation history
- **Saver**: Save/pick target selection
- **Developer**: Developer tools workspace
- **Bugreport**: Bug report workspace
- **Workspace**: Core workspace framework, tab management
- **IO**: File I/O, path system, gateway operations
- **General**: Cross-cutting concerns, architecture, build system
- **Release**: Version bumps and release plumbing
- **Fix**: Bug fixes that don't fit a specific module

## Title Guidelines

Titles appear in changelogs, so describe the user-visible effect, not internal implementation details ("Explorer: Fix breadcrumb bar not extending below cutout", not "Fix: Update CutoutCardDefaults padding values"). Use action words: "Fix", "Add", "Improve", "Update", "Remove".

## Body

- Include the technical implementation details developers need.
- Issue references: "Closes #123", "Fixes #123", or "Resolves #123".
- Mark breaking changes with a "BREAKING:" prefix.

Example:

```
Explorer: Fix breadcrumb bar not extending below cutout

The CutoutCard component wasn't accounting for the full cutout area
when rendering the breadcrumb bar overlay.

Closes #42
```

## Pull Request Titles

PR titles use the same module prefixes as commits. Title rules (user-facing language, no internal
names) are enforced by the devtools PR skill. The PR body format ("What changed" + "Technical
Context", no Validation section) is defined in the global instructions, not here.

## Pull Request Labels

Component and platform/scope labels are applied automatically by `.github/workflows/pr-labeler.yml`
from the paths a PR touches (mapping in `.github/labeler.yml`). Do not apply those by hand. If a
label is wrong or missing, the glob is wrong, so fix `labeler.yml` instead.

The labels below are the ones a human or the PR skill still has to set. Run `gh label list` to see
what exists, and do not invent new labels.

- **Type labels**: `bug` for fixes, `enhancement` for new features/improvements, `Chore` for
  refactors/tests/cleanup, `documentation` for docs-only changes. Every PR gets exactly one.
- **Device-specific labels**: `Device specific` plus the relevant ROM label (`ROM: OneUI`,
  `ROM: LOS`, `ROM: MIUI`, `ROM: HyperOS`, `ROM: ColorOS`, `ROM: OxygenOS`, `ROM: AOSP`, …) when
  fixing manufacturer/ROM-specific behavior.
- **API level labels** (`api: 26 A8.0 (Oreo)` through `api: 37 A17 (Cinnamon Bun)`): apply when the
  change targets behavior specific to certain Android versions, e.g. a scoped-storage or SAF
  restriction that only exists from a given release on.
- **Scope labels the globs cannot see**: `General UI/UX` and `F-Droid`, which describe work spread
  across modules rather than confined to a directory the mapping matches.

The type label is the only mandatory one. Skip anything from the other three groups that doesn't
clearly fit, no extra label is better than a wrong one.

Component labels are only added, never removed, by the workflow (`sync-labels: false`), so a label
you add by hand survives later pushes.
