# Commit Message Guidelines

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
- **Templates**: Workspace template management
- **Workspace**: Core workspace framework, tab management
- **IO**: File I/O, path system, gateway operations
- **General**: Cross-cutting concerns, architecture, build system
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
