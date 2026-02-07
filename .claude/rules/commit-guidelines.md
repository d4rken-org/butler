# Commit Message Guidelines

## Format

```
<module>: <user-friendly title>

<detailed technical description>

<optional additional context>

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

- **Keep user-friendly**: Titles appear in changelogs, so make them understandable to end users
- **Be specific but concise**: Describe what the user will experience, not internal implementation details
- **Use action words**: "Fix", "Add", "Improve", "Update", "Remove"

## Examples

### Good Examples

```
Explorer: Fix breadcrumb bar not extending below cutout

The CutoutCard component wasn't accounting for the full cutout area
when rendering the breadcrumb bar overlay.

Closes #42
```

```
Searcher: Add directory picker for search path selection

Users can now select a search directory using the Explorer picker
launched as a modal workspace.
```

### Bad Examples

```
Fix: Update CutoutCardDefaults padding values
```
*Too technical for changelog, should describe the user-visible effect*

```
Refactor workspace event handling for picker results
```
*No module prefix, too technical for users*

## Technical Details

- **Body**: Include technical implementation details that developers need
- **Issue references**: Use "Closes #123", "Fixes #123", or "Resolves #123"
- **Breaking changes**: Mark with "BREAKING:" prefix if applicable
