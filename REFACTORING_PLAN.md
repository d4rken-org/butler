# Butler Pre-Launch Refactoring Plan

## Scope

Based on user decisions:
- **Tiers 1 + 2** (7 items total)
- **Critical paths only** for tests (~20-30 tests)
- **Skip** Templates decoupling (explicit list is acceptable)

---

## TIER 1: Critical

### 1.1 Consolidate Duplicated DeleteOperationReport (~260 LOC)

**Problem:** Identical implementations in Explorer and Searcher.

**Files:**
- `app-workspace-explorer/src/main/java/eu/darken/butler/explorer/core/operations/DeleteOperationReport.kt`
- `app-workspace-searcher/src/main/java/eu/darken/butler/searcher/core/operations/DeleteOperationReport.kt`

**Solution:** Create generic base class in `app-workspace` module. Both modules extend with their specific `Operation.Report` type.

```kotlin
// app-workspace/core/operations/BaseDeleteOperationReport.kt
abstract class BaseDeleteOperationReport<T : Operation.Report>(
    override val affectedPaths: Collection<PathChange>,
    val skipped: Collection<APathLookup<*>>,
    // ... shared fields
) : T
```

---

### 1.2 Remove Explorer → Editor Direct Dependency

**Problem:** `app-workspace-explorer/build.gradle.kts:50` depends on Editor module.

**Current usage** in `ExplorerWorkspaceViewModel.kt`:
```kotlin
import eu.darken.butler.workspace.contracts.editor.EditorArguments
```

**Solution:** Move `EditorArguments` interface to `app-workspace` (where `Workspace.Arguments` lives), or use generic workspace creation without type-specific arguments.

**Files to modify:**
- `app-workspace-explorer/build.gradle.kts` - remove Editor dependency
- `app-workspace-explorer/.../ExplorerWorkspaceViewModel.kt` - update import
- `app-workspace/...` or `app-workspace-editor/...` - move/adjust arguments

---

### 1.3 Add Critical Path Tests (~20-30 tests)

**Target modules with zero coverage:**

| Component | Priority | Estimated Tests |
|-----------|----------|-----------------|
| ChunkedTextBuffer | High | 10-12 (CRUD, chunking, cursor) |
| EditorEngine | High | 5-8 (load, save, undo/redo) |
| BrowsingEngine | Medium | 5-6 (navigation, refresh) |
| SearcherEngine | Medium | 5-6 (search, filter) |

**Files to create:**
- `app-workspace-editor/src/test/java/.../ChunkedTextBufferTest.kt`
- `app-workspace-editor/src/test/java/.../EditorEngineTest.kt`
- `app-workspace-explorer/src/test/java/.../BrowsingEngineTest.kt`
- `app-workspace-searcher/src/test/java/.../SearcherEngineTest.kt`

---

## TIER 2: High Priority

### 2.1 Migrate java.time → kotlin.time (7 files)

**Files:**
1. `app-common/src/main/java/eu/darken/butler/common/Occasions.kt`
2. `app-common/src/main/java/eu/darken/butler/common/serialization/OffsetDateTimeAdapter.kt`
3. `app/src/main/java/eu/darken/butler/common/debug/recorder/core/RecorderManager.kt`
4. `app-workspace-apps/src/main/java/eu/darken/butler/apps/ui/details/AppInformationFields.kt`
5. `app/src/foss/java/eu/darken/butler/common/updater/GithubApi.kt`
6. `app/src/main/java/eu/darken/butler/upgrade/ui/UpgradeStatusViewModel.kt`
7. `app-common/src/test/java/eu/darken/butler/common/OccasionsTest.kt`

**Migration:**
- `java.time.Instant` → `kotlin.time.Instant`
- `java.time.LocalDate` → `kotlinx.datetime.LocalDate`
- `java.time.DayOfWeek` → `kotlinx.datetime.DayOfWeek`

---

### 2.2 Remove Build Configuration Duplicates

**Problem:** `addNavigation3()` called twice in 4 files.

**Files:**
- `app-common/build.gradle.kts` - remove line 71
- `app-workspace-explorer/build.gradle.kts` - remove line 61
- `app-workspace-developer/build.gradle.kts` - remove line 59
- `app-workspace-templates/build.gradle.kts` - remove line 64

---

### 2.3 Add Missing @Preview2 Functions (3 files)

**Files missing required previews:**

1. **`app-workspace-editor/src/main/java/eu/darken/butler/editor/ui/editor/text/LazyTextEditor.kt`** (838 lines)
   - Add: Empty state, Loading, With content, Error state previews

2. **`app-workspace/src/main/java/eu/darken/butler/workspace/ui/clipboard/details/ClipboardInfoBottomSheet.kt`** (866 lines)
   - Add: Empty clipboard, Single item, Multiple items previews

3. **`app-workspace/src/main/java/eu/darken/butler/workspace/ui/operations/bar/OperationEntryRow.kt`** (614 lines)
   - Add: Running, Completed, Failed, Progress previews

---

### 2.4 Audit @OptIn Annotations

**Problem:** 222 `@OptIn` annotations, many unnecessary due to project-wide compiler flags.

**High-priority files:**
- `app-common/src/main/java/eu/darken/butler/common/flow/FlowCombineExtensions.kt` (18 annotations)
- `app-common-io/src/main/java/eu/darken/butler/common/files/GatewaySwitch.kt` (14 annotations)

**Action:** Remove `@OptIn` for APIs already enabled in `build.gradle.kts` compiler options.

---

## Implementation Order

```
Phase 1 (Quick wins):
  2.2 Build duplicates (5 min)
  2.4 @OptIn audit (1-2 hours)

Phase 2 (Architecture):
  1.1 DeleteOperationReport consolidation (2-3 hours)
  1.2 Explorer→Editor decoupling (1-2 hours)

Phase 3 (Quality):
  1.3 Critical path tests (4-6 hours)
  2.3 Preview functions (2-3 hours)
  2.1 kotlin.time migration (2-3 hours)
```

---

## Summary

| # | Item | Files | Effort |
|---|------|-------|--------|
| 1.1 | Consolidate DeleteOperationReport | 2-3 files | 2-3h |
| 1.2 | Remove Explorer→Editor dep | 2-3 files | 1-2h |
| 1.3 | Add critical tests | 4 new test files | 4-6h |
| 2.1 | kotlin.time migration | 7 files | 2-3h |
| 2.2 | Build duplicates | 4 files | 5min |
| 2.3 | Preview functions | 3 files | 2-3h |
| 2.4 | @OptIn audit | 2+ files | 1-2h |

**Total estimated effort:** 12-20 hours
