# CLAUDE.md

This file provides guidance to AI tools (e.g. Claude Code, Codex) when working with code in this repository.

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
| Build, debug commands | `.claude/rules/development-commands.md` |
| Module structure | `.claude/rules/architecture.md` |
| Modal workspace pattern | `.claude/rules/architecture-modal-workspaces.md` |
| Code style, composables | `.claude/rules/coding-standards.md` |
| Testing | `.claude/rules/testing.md` |
| UI, theming | `.claude/rules/ui-guidelines.md` |
| Localization | `.claude/rules/localization.md` |
| DI, logging, serialization | `.claude/rules/technical-patterns.md` |
| Screenshots | `.claude/rules/screenshots.md` |
| Agent delegation | `.claude/rules/agent-instructions.md` |
| Commit messages | `.claude/rules/commit-guidelines.md` |

## Quick Commands

```bash
# Build (FOSS flavor)
./gradlew :app:compileFossDebugKotlin --no-daemon

# Test
./gradlew testDebugUnitTest

# Lint
./gradlew lintVitalFossRelease
```

See `.claude/rules/development-commands.md` for complete command reference.

## Important File Locations

### Database Schemas
- `app/schemas/`: Room database schema files for migrations
- `app-common-io/schemas/`: SAF location and trash database schemas
- `app-workspace-searcher/schemas/`: Search history database schemas
- `app-workspace/schemas/`: Workspace session database schemas

### Localization
- `app-common/src/main/res/values/strings.xml`: Shared base English strings
- `<module>/src/main/res/values/strings.xml`: Module-specific strings

### Build Configuration
- `buildSrc/src/main/java/Versions.kt`: Dependency versions
- `buildSrc/build.gradle.kts`: Build plugin versions
- `.github/workflows/code-checks.yml`: CI configuration

### Screenshots
- `app/src/screenshotTest/kotlin/.../screenshots/`: Compose screenshot test definitions
- `app/src/debug/java/.../screenshots/ScreenshotContent.kt`: Mock content for screenshots
- `fastlane/generate_screenshots.sh`: Batch screenshot generation script
- `fastlane/copy_screenshots.sh`: Copies screenshots to fastlane metadata
- `fastlane/metadata/android/{locale}/images/phoneScreenshots/`: Play Store screenshots

### Build Flavors
- **FOSS**: Open source version without Google Play dependencies
- **GPLAY**: Google Play version with additional features
- Use FOSS debug flavor for local development
