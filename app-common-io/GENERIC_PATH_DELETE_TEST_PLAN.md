# GenericPathDeleteTest Implementation Plan

## Executive Summary

This document outlines the plan to implement
`GenericPathDeleteTest` to complete the test coverage pattern established for generic file operations.

**Status**: Missing test file identified
**Priority**: Medium (architectural consistency)
**Estimated Effort**: 2-3 hours

---

## Problem Statement

### Current State

| Operation  | Generic Test (MockFileSystemOps)     | Local Test (Real Files)              |
|------------|--------------------------------------|--------------------------------------|
| Copy       | ✓ `GenericPathCopyTest` (947 lines)  | ✓ `LocalPathCopyTest`                |
| Move       | ✓ `GenericPathMoveTest` (1117 lines) | ✓ `LocalPathMoveTest`                |
| **Delete** | **❌ MISSING**                        | ✓ `LocalPathDeleteTest` (1830 lines) |

### Why This Matters

1. **Pattern Completion**: Copy and Move establish a clear testing pattern (generic + local tests)
2. **Independent Validation**: Generic tests validate orchestrator logic without real file system dependencies
3. **Fast Feedback**: MockFileSystemOps tests run faster and more reliably than real file I/O
4. **Architectural Consistency**: Delete deserves the same test rigor as Copy and Move
5. **Test Coverage**: `LocalPathDeleteTest` validates the implementation, but not the generic algorithm itself

---

## Architecture Overview

### GenericPathDelete Key Features

Based on `GenericPathDelete.kt` analysis:

```
┌─────────────────────────────────────────────────────────┐
│          GenericPathDelete Algorithm                     │
├─────────────────────────────────────────────────────────┤
│                                                          │
│  Phase 1: SCAN                                          │
│  ├─ Walk tree (depth-first)                             │
│  ├─ Calculate total items/bytes                         │
│  ├─ Queue DeletePath work items (deferred)              │
│  └─ Report scan progress (throttled)                    │
│                                                          │
│  Phase 2: DELETE                                        │
│  ├─ Process DeletePath work items                       │
│  ├─ Delete in POST-ORDER (children before parents)      │
│  ├─ Handle errors with apply-to-all                     │
│  └─ Report delete progress (throttled)                  │
│                                                          │
│  Key Components:                                        │
│  ├─ WorkQueue (ScanPath, DeletePath)                    │
│  ├─ PathOperationProgressTracker (throttling)           │
│  ├─ PathOperationIssueResolver (apply-to-all)          │
│  └─ Two-phase execution (scan then delete)              │
│                                                          │
└─────────────────────────────────────────────────────────┘
```

### What Makes Delete Different

Unlike Copy/Move, Delete:

- **No destination path**: Single source, no path calculations needed
- **Post-order critical**: Directories MUST be deleted after children
- **Two-phase workflow**: Scan builds work queue, then execute deletions
- **Simpler semantics**: No "rename vs merge" conflicts

---

## Test Structure Plan

### File Location

```
app-common-io/src/test/java/eu/darken/butler/common/files/operations/GenericPathDeleteTest.kt
```

### Test Categories

Based on `GenericPathCopyTest` and `GenericPathMoveTest` structure:

#### 1. Basic Deletion Operations (150 lines)

- `delete single file`
- `delete empty directory`
- `delete nested directory structure`
- `delete mixed files and directories`
- `delete root-level file`
- `delete deeply nested structure (10 levels)`

#### 2. Post-Order Deletion Validation (100 lines)

- `verify children deleted before parents`
- `deletion order tracked correctly`
- `empty directories deleted after scanning children`
- `complex tree deleted in correct order`

#### 3. Recursive Flag Behavior (150 lines)

- `recursive=true deletes entire tree`
- `recursive=false deletes only empty directories`
- `recursive=false with non-empty directory fails appropriately`
- `recursive flag with mixed content`

#### 4. IgnoreMissing Flag Behavior (100 lines)

- `ignoreMissing=true skips non-existent files`
- `ignoreMissing=false throws on non-existent files`
- `mixed existing and non-existing files`
- `file deleted between scan and delete phases`

#### 5. Progress Reporting (150 lines)

- `scan progress reports total items found`
- `delete progress reports items processed`
- `progress callbacks throttled (not every file)`
- `final progress shows completion`
- `progress count accurate with workQueue architecture`
- `progress never regresses during operation`

#### 6. Error Handling & Retry (200 lines)

- `transient error with retry succeeds`
- `persistent error after max retries skips`
- `retry does not regress progress tracking`
- `permission error with skip resolution`
- `permission error with apply-to-all`
- `unknown error with skip resolution`
- `unknown error with apply-to-all`

#### 7. Result Verification (100 lines)

- `result contains deleted items`
- `result contains skipped items`
- `deleted and skipped sets are mutually exclusive` ⭐ (critical for bug we fixed)
- `bytes deleted tracked correctly`
- `empty collection returns empty result`

#### 8. Edge Cases (100 lines)

- `delete collection with duplicates`
- `delete multiple targets with error in one`
- `very deep directory structure (50 levels)`
- `large number of files (1000+)`
- `concurrent deletion handling`

#### 9. Symlink Handling (optional, 100 lines)

- `delete symlink to file (not target)`
- `delete broken symlink succeeds`
- `delete directory containing symlinks`
- `delete symlink chain`

---

## Implementation Steps

### Step 1: Create Test File Skeleton (30 min)

```kotlin
package eu.darken.butler.common.files.operations

import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.actions.DeleteAction
import eu.darken.butler.common.files.actions.PathActionIssue
import eu.darken.butler.common.files.local.LocalPathLookup
import eu.darken.butler.common.files.local.LocalPathLookupExtended
import eu.darken.butler.common.files.metadata.FileType
import io.kotest.matchers.shouldBe
import io.kotest.matchers.collections.shouldContain
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import testhelpers.BaseTest

/**
 * Tests for GenericPathDelete - the high-level delete orchestrator.
 *
 * Tests the complete delete operation including:
 * - Scanning source trees
 * - Post-order deletion (children before parents)
 * - Two-phase workflow (scan then delete)
 * - Progress reporting with throttling
 * - Error handling with apply-to-all
 * - Retry functionality
 *
 * Uses MockFileSystemOps to test without real file system access.
 */
class GenericPathDeleteTest : BaseTest() {

    private lateinit var mockOps: MockFileSystemOps<LocalPath, LocalPathLookup, LocalPathLookupExtended>

    @BeforeEach
    fun setup() {
        mockOps = MockFileSystemOps { path, type, size, modifiedAt, permissions, ownership ->
            LocalPathLookup(
                lookedUp = path,
                fileType = type,
                size = size,
                modifiedAt = modifiedAt ?: kotlin.time.Instant.fromEpochMilliseconds(0),
                target = null
            )
        }
    }

    @AfterEach
    fun cleanup() {
        mockOps.clear()
    }

    // ============ BASIC DELETION OPERATIONS ============

    // ============ POST-ORDER DELETION VALIDATION ============

    // ============ RECURSIVE FLAG BEHAVIOR ============

    // ============ IGNORE MISSING FLAG BEHAVIOR ============

    // ============ PROGRESS REPORTING ============

    // ============ ERROR HANDLING & RETRY ============

    // ============ RESULT VERIFICATION ============

    // ============ EDGE CASES ============
}
```

### Step 2: Implement Basic Tests (1 hour)

Start with fundamental operations:

1. Delete single file
2. Delete empty directory
3. Delete nested structure
4. Verify post-order deletion

**Success Criteria**: Basic deletion works and order is validated

### Step 3: Implement Flag Tests (45 min)

Cover recursive and ignoreMissing combinations:

1. recursive=true/false behavior
2. ignoreMissing=true/false behavior
3. All 4 combinations tested

**Success Criteria**: Flag behavior matches specification

### Step 4: Implement Progress & Error Tests (1 hour)

Most complex section:

1. Progress throttling validation
2. Retry mechanism testing
3. Apply-to-all error handling
4. Progress regression prevention

**Success Criteria**: All progress/error scenarios pass

### Step 5: Implement Result Verification (30 min)

Critical for correctness:

1. Deleted vs skipped mutual exclusivity
2. Accurate byte counting
3. Edge case handling

**Success Criteria**: Result objects accurate in all scenarios

---

## Key Test Cases to Implement

### Critical Tests (Must Have)

#### Test: Post-Order Deletion Verification

```kotlin
@Test
fun `verify children deleted before parents in nested structure`() = runTest {
        // Given - nested structure: parent/child/grandchild/file.txt
        mockOps.addMockDir("/parent")
        mockOps.addMockDir("/parent/child")
        mockOps.addMockDir("/parent/child/grandchild")
        mockOps.addMockFile("/parent/child/grandchild/file.txt", "content".toByteArray())

        val deletionOrder = mutableListOf<String>()

        // Track deletion order via spy
        val spyOps = /* create spy that tracks delete() calls */

            // When
            LocalPath.build("/parent").deleteGeneric(spyOps)

        // Then - verify post-order: file, grandchild, child, parent
        deletionOrder shouldBe listOf(
            "/parent/child/grandchild/file.txt",
            "/parent/child/grandchild",
            "/parent/child",
            "/parent"
        )
    }
```

#### Test: Deleted/Skipped Mutual Exclusivity

```kotlin
@Test
fun `deleted and skipped sets are mutually exclusive`() = runTest {
        // Given - multiple files, some will fail
        mockOps.addMockFile("/file1.txt", "content1".toByteArray())
        mockOps.addMockFile("/file2.txt", "content2".toByteArray())
        mockOps.addMockFile("/file3.txt", "content3".toByteArray())

        // Inject failure for file2
        mockOps.setFailDelete(1) // Fail once for file2

        // When - skip on error
        val result = listOf(
            LocalPath.build("/file1.txt"),
            LocalPath.build("/file2.txt"),
            LocalPath.build("/file3.txt")
        ).deleteGeneric(
            mockOps,
            onIssue = { PathActionIssue.UnknownError.Resolution.Skip() }
        )

        // Then - verify no overlap
        val deletedPaths = result.deleted.map { it.lookedUp }.toSet()
        val skippedPaths = result.skipped.map { it.lookedUp }.toSet()

        deletedPaths.intersect(skippedPaths).shouldBeEmpty()
        deletedPaths shouldContain LocalPath.build("/file1.txt")
        deletedPaths shouldContain LocalPath.build("/file3.txt")
        skippedPaths shouldContain LocalPath.build("/file2.txt")
    }
```

#### Test: Progress Throttling

```kotlin
@Test
fun `progress callbacks throttled to reduce overhead`() = runTest {
        // Given - 100 files that would generate 200+ callbacks without throttling
        repeat(100) { i ->
            mockOps.addMockFile("/file$i.txt", "content".toByteArray())
        }

        val progressCallCount = AtomicInteger(0)

        // When
        (0 until 100).map { LocalPath.build("/file$it.txt") }.deleteGeneric(
            mockOps,
            onProgress = { progressCallCount.incrementAndGet() }
        )

        // Then - significantly fewer than 200 calls (with 250ms throttling)
        progressCallCount.get() should { it < 80 }
    }
```

#### Test: Retry Mechanism

```kotlin
@Test
fun `delete retry resolution works after transient error`() = runTest {
        // Given
        mockOps.addMockFile("/file.txt", "content".toByteArray())

        // Fail once, then succeed
        mockOps.setFailDelete(1)

        var issueCount = 0

        // When
        val result = LocalPath.build("/file.txt").deleteGeneric(
            mockOps,
            onIssue = { issue ->
                issueCount++
                PathActionIssue.UnknownError.Resolution.Retry
            }
        )

        // Then - succeeded after retry
        result.deleted.size shouldBe 1
        issueCount shouldBe 1 // One issue encountered and retried
        mockOps.hasFile("/file.txt") shouldBe false
    }
```

### Nice-to-Have Tests

- Symlink handling (if time permits)
- Performance benchmarks (1000+ files)
- Concurrent deletion edge cases

---

## Testing Strategy

### Unit Test Focus

This test file validates:

- ✅ Algorithm correctness (scan → delete workflow)
- ✅ Post-order deletion enforcement
- ✅ Progress tracking accuracy
- ✅ Error handling logic
- ✅ Result object correctness

This test file does NOT validate:

- ❌ Real file system operations (covered by `LocalPathDeleteTest`)
- ❌ Android-specific functionality
- ❌ UI/UX behavior

### Test Execution

```bash
# Run only GenericPathDeleteTest
./gradlew :app-common-io:testDebugUnitTest --tests "*GenericPathDeleteTest"

# Run all generic operation tests
./gradlew :app-common-io:testDebugUnitTest --tests "*GenericPath*Test"
```

---

## Success Criteria

### Completion Checklist

- [ ] Test file created with proper structure
- [ ] All basic deletion operations covered
- [ ] Post-order deletion validated
- [ ] Recursive flag behavior tested
- [ ] IgnoreMissing flag behavior tested
- [ ] Progress reporting validated
- [ ] Progress throttling confirmed
- [ ] Error handling tested (skip, retry, cancel)
- [ ] Apply-to-all functionality verified
- [ ] Result accuracy validated
- [ ] Deleted/skipped mutual exclusivity verified
- [ ] All tests pass locally
- [ ] Code coverage ≥ 90% for GenericPathDelete.kt

### Quality Gates

1. **Test Count**: Target 40-50 tests (similar to Copy/Move)
2. **Line Count**: Target 800-1000 lines (comprehensive but focused)
3. **Execution Time**: < 5 seconds total (fast with MockFileSystemOps)
4. **Coverage**: ≥ 90% line coverage on `GenericPathDelete.kt`

---

## Risk Assessment

### Low Risk

- ✅ MockFileSystemOps already supports delete operations
- ✅ Pattern established by Copy and Move tests
- ✅ LocalPathDeleteTest provides reference implementation

### Medium Risk

- ⚠️ Post-order deletion tracking may need spy/wrapper implementation
- ⚠️ Progress throttling timing may be non-deterministic

### Mitigation Strategies

- Use spy pattern from `GenericPathMoveTest:386-417` for deletion order tracking
- Use count-based assertions for progress (not timing-based)
- Focus on algorithm correctness, not performance metrics

---

## Implementation Notes

### MockFileSystemOps Capabilities

Already supports (lines 204-239):

- ✅ `delete(path, recursive)` with post-order logic
- ✅ Failure injection via `setFailDelete()`
- ✅ Deletion tracking via `deleteCalls` list
- ✅ Non-empty directory detection

### Code Reuse Opportunities

Borrow patterns from:

- `GenericPathCopyTest`: Setup/teardown, progress validation
- `GenericPathMoveTest`: Spy pattern for operation order tracking (lines 393-417)
- `LocalPathDeleteTest`: Edge case scenarios (symlinks, permissions)

### Unique Challenges

1. **Two-phase workflow**: Need to validate scan completes before delete starts
2. **Post-order enforcement**: Critical for correctness, must verify order
3. **Progress accuracy**: Scan vs delete progress must be distinguishable

---

## Timeline

| Phase                  | Duration      | Deliverable                         |
|------------------------|---------------|-------------------------------------|
| Setup & Basic Tests    | 1.5 hours     | File structure + basic operations   |
| Flag Behavior Tests    | 45 min        | Recursive + ignoreMissing coverage  |
| Progress & Error Tests | 1 hour        | Throttling + retry + apply-to-all   |
| Result & Edge Cases    | 45 min        | Result validation + edge cases      |
| Review & Refinement    | 30 min        | Code review, cleanup, documentation |
| **Total**              | **4.5 hours** | Complete test file ready for commit |

---

## References

### Source Files

- `app-common-io/src/main/java/eu/darken/butler/common/files/operations/GenericPathDelete.kt` (445 lines)
- `app-common-io/src/test/java/eu/darken/butler/common/files/operations/GenericPathCopyTest.kt` (947 lines)
- `app-common-io/src/test/java/eu/darken/butler/common/files/operations/GenericPathMoveTest.kt` (1117 lines)
- `app-common-io/src/test/java/eu/darken/butler/common/files/operations/MockFileSystemOps.kt` (661 lines)
- `app-common-io/src/test/java/eu/darken/butler/common/files/local/LocalPathDeleteTest.kt` (1830 lines)

### Related Commits

- `7e6c76bd9` - Test: Add comprehensive retry tests for file operations
- `1f0b0b3f8` - Feat: Implement IPC file operations framework (Phase 1-2: Foundation + Delete)

---

## Next Steps

1. **Review this plan** with team/stakeholders
2. **Allocate time** for implementation (recommend one focused session)
3. **Create implementation branch**: `test/generic-path-delete-test`
4. **Implement tests** following the structure outlined above
5. **Run full test suite** to ensure no regressions
6. **Code review** focusing on test coverage and clarity
7. **Merge** when all tests pass and coverage targets met

---

*Document Version: 1.0*
*Last Updated: 2025-10-16*
*Author: Claude Code Analysis*
