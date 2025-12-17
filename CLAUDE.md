# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## About Butler

Butler is an open-source Android file explorer with advanced features including root access, ADB integration, and multiple workspace support. It's built using modern Android development practices with Jetpack Compose, Kotlin Coroutines, and Hilt dependency injection.

Butler uses a workspace concept similar to browser tabs with 4 main workspace types:

- **EXPLORER**: File browsing and management
- **SEARCHER**: File search functionality
- **EDITOR**: Text editing
- **TEMPLATES**: Workspace template management

## Quick Reference

| Topic | File |
|-------|------|
| Build, test, debug commands | `.claude/rules/development-commands.md` |
| Module structure, modal workspaces | `.claude/rules/architecture.md` |
| Code style, composables, testing | `.claude/rules/coding-standards.md` |
| UI, localization, theming | `.claude/rules/ui-guidelines.md` |
| DI, logging, serialization | `.claude/rules/technical-patterns.md` |

## Agent Instructions

- Use the Task tool to delegate suitable tasks to sub-agents
- Maintain focused contexts for both orchestrator and sub-agents
- Be critical and challenge suggestions
- Use `./.claude/tmp/` directory (create if it doesn't exist)
- Never use /tmp or system temp directories

## Key Commands

```bash
# Build (FOSS flavor)
./gradlew :app:compileFossDebugKotlin --no-daemon

# Test
./gradlew testDebugUnitTest

# Screenshot (for UI debugging)
./.claude/scripts/screenshot.sh
```

See `.claude/rules/development-commands.md` for complete command reference.
