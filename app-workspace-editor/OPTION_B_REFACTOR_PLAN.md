# Option B Refactor Plan: Complete Boundary-Based Chunk Tracking

## Executive Summary

This document outlines the remaining work to complete Option B: migrating from offset-based chunk IDs to sequential IDs with boundary tracking.

**Current Status**: 128/142 tests passing (90%)
**Remaining Work**: Remove dual source of truth for chunk positions
**Estimated Effort**: 30-40 lines of code, 30-45 minutes
**Expected Outcome**: All 142 tests passing

---

## Current Architecture (Partial Implementation)

### What's Done ✅
- Sequential chunk ID generation (`chunk_0`, `chunk_1`, `chunk_2`)
- `ChunkBoundary` data class and `boundaries` map infrastructure
- Boundary-based chunk loading in `ChunkRepository`
- `updateBoundaries()` method to shift positions after edits
- Deadlock fix: proper call ordering in edit operations
- 128/142 tests passing with no deadlocks

### The Problem ❌
**Dual Source of Truth for Chunk Positions**

Currently, chunk positions are stored in TWO places:

1. **TextChunk.startOffset/endOffset** - Original design, represents content positions
2. **boundaries map** - New design, tracks actual file positions

After edit operations, these become inconsistent:
- `updateBoundaries()` updates the boundaries map
- TextChunk objects keep their original offsets
- `buildChunkMetadata()` reads from chunk offsets (stale data)
- Results in 14 test failures related to undo/redo and eviction

**Example Failure Scenario**:
```kotlin
// Initial state
chunk_0: content="Hello", startOffset=0, endOffset=5
boundary[chunk_0] = ChunkBoundary(0, 5)

// After inserting " World" at end
chunk_0: content="Hello World", startOffset=0, endOffset=11  // Updated by updateChunk()
boundary[chunk_0] = ChunkBoundary(0, 11)  // Updated by updateBoundaries()

// After chunk_0 is evicted and reloaded
// loadChunk() uses boundary (correct: 0-11)
// But buildChunkMetadata() uses chunk.startOffset/endOffset
// If chunk wasn't evicted, it still has old offsets!
// → Inconsistency causes test failures
```

---

## Solution: Make Boundaries the Single Source of Truth

Remove `startOffset` and `endOffset` from `TextChunk`, use boundaries map exclusively.

### Design Principles
1. **Chunk IDs are opaque identifiers** - they never change
2. **Boundaries map owns position data** - authoritative source for where chunks are in the file
3. **TextChunk owns content data** - what text it holds, whether it's dirty, loaded state
4. **ChunkMetadata derives from boundaries** - calculated on-demand, never stored in chunks

---

## Detailed Refactor Steps

### Step 1: Update TextChunk Data Class
**File**: `app-workspace-editor/src/main/java/eu/darken/butler/editor/core/engine/TextChunk.kt`

**Change**:
```kotlin
// BEFORE
data class TextChunk(
    val id: ChunkId,
    val startOffset: Long,      // ❌ Remove
    val endOffset: Long,         // ❌ Remove
    val content: String,
    val lineCount: Int,
    val isDirty: Boolean = false,
    val isLoaded: Boolean = true
) {
    val size: Long get() = endOffset - startOffset  // ❌ Remove
    val isEmpty: Boolean get() = content.isEmpty()

    fun containsOffset(offset: Long): Boolean = offset in startOffset until endOffset  // ❌ Remove
    // ...
}

// AFTER
data class TextChunk(
    val id: ChunkId,
    val content: String,
    val lineCount: Int,
    val isDirty: Boolean = false,
    val isLoaded: Boolean = true
) {
    val size: Long get() = content.length.toLong()  // ✅ Use content length
    val isEmpty: Boolean get() = content.isEmpty()

    // Remove containsOffset() - use boundaries map instead
    // ...
}
```

**Impact**: ~10 lines changed

---

### Step 2: Update ChunkManager
**File**: `app-workspace-editor/src/main/java/eu/darken/butler/editor/core/engine/ChunkManager.kt`

**Changes**:

#### 2a. Add helper to get boundary
```kotlin
/**
 * Get the boundary for a chunk ID.
 * Boundaries are the authoritative source for chunk positions.
 */
fun getBoundary(chunkId: ChunkId): ChunkBoundary? {
    return boundaries[chunkId]
}
```

#### 2b. Update updateBoundaries() signature (OPTIONAL)
No changes needed - it already only updates boundaries map.

**Impact**: ~5 lines added

---

### Step 3: Update ChunkRepository.loadChunk()
**File**: `app-workspace-editor/src/main/java/eu/darken/butler/editor/core/engine/ChunkRepository.kt`

**Change**:
```kotlin
// BEFORE
suspend fun loadChunk(chunkId: TextChunk.ChunkId, boundary: ChunkBoundary): TextChunk = withContext(Dispatchers.IO) {
    log(tag) { "Loading chunk: $chunkId at ${boundary.startOffset}-${boundary.endOffset}" }

    val content = dataSource.readChunk(boundary.startOffset, boundary.size)
    val lineCount = content.count { it == '\n' } + if (content.isNotEmpty() && !content.endsWith('\n')) 1 else 0

    val chunk = TextChunk(
        id = chunkId,
        startOffset = boundary.startOffset,  // ❌ Remove
        endOffset = boundary.endOffset,      // ❌ Remove
        content = content,
        lineCount = lineCount,
        isDirty = false,
        isLoaded = true
    )

    log(tag) { "Loaded chunk: $chunkId (${chunk.size} bytes, $lineCount lines)" }
    chunk
}

// AFTER
suspend fun loadChunk(chunkId: TextChunk.ChunkId, boundary: ChunkBoundary): TextChunk = withContext(Dispatchers.IO) {
    log(tag) { "Loading chunk: $chunkId at ${boundary.startOffset}-${boundary.endOffset}" }

    val content = dataSource.readChunk(boundary.startOffset, boundary.size)
    val lineCount = content.count { it == '\n' } + if (content.isNotEmpty() && !content.endsWith('\n')) 1 else 0

    val chunk = TextChunk(
        id = chunkId,
        content = content,
        lineCount = lineCount,
        isDirty = false,
        isLoaded = true
    )

    log(tag) { "Loaded chunk: $chunkId (${content.length} bytes, $lineCount lines)" }
    chunk
}
```

**Impact**: ~3 lines removed

---

### Step 4: Update ChunkedTextBuffer.initialize()
**File**: `app-workspace-editor/src/main/java/eu/darken/butler/editor/core/engine/ChunkedTextBuffer.kt`

**Change**:
```kotlin
// BEFORE (around line 77-89)
if (size == 0L && chunkIds.isNotEmpty()) {
    val emptyChunkId = chunkIds.first()
    val emptyChunk = TextChunk(
        id = emptyChunkId,
        startOffset = 0L,      // ❌ Remove
        endOffset = 0L,         // ❌ Remove
        content = "",
        lineCount = 1,
        isDirty = false,
        isLoaded = true
    )
    chunkManager.addChunk(emptyChunk)
}

// AFTER
if (size == 0L && chunkIds.isNotEmpty()) {
    val emptyChunkId = chunkIds.first()
    val emptyChunk = TextChunk(
        id = emptyChunkId,
        content = "",
        lineCount = 1,
        isDirty = false,
        isLoaded = true
    )
    chunkManager.addChunk(emptyChunk)
}
```

**Impact**: ~2 lines removed

---

### Step 5: Update ChunkedTextBuffer.buildChunkMetadata()
**File**: `app-workspace-editor/src/main/java/eu/darken/butler/editor/core/engine/ChunkedTextBuffer.kt`

**Change**:
```kotlin
// BEFORE (around line 689-712)
for ((index, chunkId) in chunkIds.withIndex()) {
    val chunk = chunkManager.getChunk(chunkId)
        ?: chunkManager.loadChunk(chunkId).getOrThrow()

    // Use actual chunk offsets instead of extracting from chunk ID
    // This handles cases where chunks have been modified/merged after deletion
    val chunkStart = chunk.startOffset  // ❌ Wrong - uses stale data
    val chunkEnd = chunk.endOffset      // ❌ Wrong - uses stale data

    val content = chunk.content.toByteArray()
    val isLastChunk = index == chunkIds.size - 1
    val lineCount = content.count { it == '\n'.code.toByte() } +
        if (isLastChunk && content.isNotEmpty() && content.last() != '\n'.code.toByte()) 1 else 0

    chunkMetadata.add(
        ChunkMetadata(
            chunkId = chunkId,
            startOffset = chunkStart,   // ❌ Wrong
            endOffset = chunkEnd,       // ❌ Wrong
            lineCount = lineCount,
            firstLineNumber = totalLines
        )
    )

    totalLines += lineCount
}

// AFTER
for ((index, chunkId) in chunkIds.withIndex()) {
    val chunk = chunkManager.getChunk(chunkId)
        ?: chunkManager.loadChunk(chunkId).getOrThrow()

    // Get authoritative boundary data from ChunkManager
    val boundary = chunkManager.getBoundary(chunkId)
        ?: throw IllegalStateException("No boundary for chunk $chunkId")

    val content = chunk.content.toByteArray()
    val isLastChunk = index == chunkIds.size - 1
    val lineCount = content.count { it == '\n'.code.toByte() } +
        if (isLastChunk && content.isNotEmpty() && content.last() != '\n'.code.toByte()) 1 else 0

    chunkMetadata.add(
        ChunkMetadata(
            chunkId = chunkId,
            startOffset = boundary.startOffset,  // ✅ Use boundary
            endOffset = boundary.endOffset,      // ✅ Use boundary
            lineCount = lineCount,
            firstLineNumber = totalLines
        )
    )

    totalLines += lineCount
}
```

**Impact**: ~5 lines changed

---

### Step 6: Update ChunkedTextBuffer edit operations
**File**: `app-workspace-editor/src/main/java/eu/darken/butler/editor/core/engine/ChunkedTextBuffer.kt`

**Changes**:

#### 6a. insertText() - Remove startOffset/endOffset updates
```kotlin
// BEFORE (around line 270-272)
val updatedChunk = loadedChunk.copy(
    content = newContent,
    endOffset = loadedChunk.endOffset + text.length,  // ❌ Remove
    isDirty = true
)

// AFTER
val updatedChunk = loadedChunk.copy(
    content = newContent,
    isDirty = true
)
```

#### 6b. deleteText() - Single chunk case
```kotlin
// BEFORE (around line 333-337)
val updatedChunk = loadedChunk.copy(
    content = newContent,
    endOffset = loadedChunk.endOffset - (endPosition.offset - startPosition.offset),  // ❌ Remove
    isDirty = true
)

// AFTER
val updatedChunk = loadedChunk.copy(
    content = newContent,
    isDirty = true
)
```

#### 6c. deleteText() - Multi-chunk case
```kotlin
// BEFORE (around line 368-372)
val updatedFirstChunk = loadedFirst.copy(
    content = mergedContent,
    endOffset = loadedFirst.startOffset + mergedContent.length,  // ❌ Remove - doesn't make sense without startOffset
    isDirty = true
)

// AFTER
val updatedFirstChunk = loadedFirst.copy(
    content = mergedContent,
    isDirty = true
)
```

**Impact**: ~6 lines removed

---

### Step 7: Update Test Files
**Files**:
- `app-workspace-editor/src/test/java/eu/darken/butler/editor/core/engine/ChunkManagerTest.kt`
- Any other tests that create TextChunk directly

**Change**: Remove `startOffset` and `endOffset` parameters from TextChunk construction in tests.

**Estimated Impact**: ~30 test file changes (sed can handle this)

**Command**:
```bash
# This will need manual review - tests construct chunks with explicit offsets
# Search for all TextChunk constructions and remove offset parameters
```

---

## Testing Strategy

### Before Refactor
Run full test suite and document current state:
```bash
./gradlew :app-workspace-editor:testDebugUnitTest --no-daemon
```
Expected: 128/142 passing

### After Each Step
Run tests incrementally to catch issues early:
```bash
# After Step 1-2: Compilation should pass
./gradlew :app-workspace-editor:compileDebugKotlin --no-daemon

# After Step 3-6: Tests should improve
./gradlew :app-workspace-editor:testDebugUnitTest --no-daemon
```

### Expected Test Results by Step
- **After Step 1-3**: Compilation works, ~100 tests passing (some will fail due to incomplete refactor)
- **After Step 4-6**: ~135-140 tests passing
- **After Step 7**: All 142 tests passing

### Key Tests to Watch
1. **Undo/Redo tests** - Most affected by boundary changes
2. **Eviction tests** - The original reason for Option B
3. **Multi-chunk operations** - Rely heavily on position tracking
4. **buildChunkMetadata accuracy** - Critical for line number calculations

---

## Potential Risks & Mitigations

### Risk 1: Breaking External APIs
**Risk**: If TextChunk is exposed publicly, removing fields breaks API.
**Mitigation**: TextChunk appears to be internal to editor module. Check with `grep -r "TextChunk" app/` to verify.

### Risk 2: Performance Impact
**Risk**: Looking up boundaries map on every position query.
**Mitigation**: Map lookups are O(1), negligible impact. Boundaries are already being looked up in many places.

### Risk 3: Test Coverage Gaps
**Risk**: Tests might not cover all edge cases of boundary tracking.
**Mitigation**: Phase 7D (concurrent access tests) will add coverage. Current 90% passing rate is strong signal.

### Risk 4: ChunkMetadata.startOffset/endOffset Confusion
**Risk**: ChunkMetadata still has startOffset/endOffset - might confuse future developers.
**Mitigation**: Add KDoc comments clarifying these are derived from boundaries, not stored in chunks.

---

## Rollback Plan

If issues arise during refactor:

1. **Immediate Rollback**:
   ```bash
   git reset --hard HEAD~1
   ```

2. **Selective Revert**: If only some steps cause issues, revert specific files:
   ```bash
   git checkout HEAD -- path/to/file.kt
   ```

3. **Partial Implementation**: If Step 7 (test updates) takes too long, commit Steps 1-6 with:
   - 14 test failures documented
   - Tests disabled temporarily
   - TODO comments for next iteration

---

## Post-Refactor Validation

### Checklist
- [ ] All 142 tests passing
- [ ] No compiler warnings introduced
- [ ] Eviction bugs fixed (original goal)
- [ ] No performance regression (run test suite timing)
- [ ] Documentation updated (KDoc comments accurate)
- [ ] CLAUDE.md updated if needed

### Performance Baseline
Before refactor, measure test suite time:
```bash
time ./gradlew :app-workspace-editor:testDebugUnitTest --no-daemon
```

After refactor, compare. Should be within 5-10% variance.

---

## Future Enhancements (Post-Refactor)

Once Option B is complete and stable:

1. **Phase 7D**: Add concurrent access tests
2. **Chunk Splitting**: Handle chunks that grow beyond size limit
3. **Chunk Merging**: Combine small adjacent chunks after deletions
4. **Metrics**: Add telemetry for chunk cache hit/miss rates
5. **Adaptive Chunk Size**: Tune chunk size based on file characteristics

---

## References

- **Original Issue**: Chunk eviction bugs causing data loss
- **Option B Analysis**: See conversation summary for full architectural discussion
- **Test Results**: 128/142 passing (baseline before completing refactor)
- **Related Files**:
  - `TextChunk.kt` - Core data structure
  - `ChunkManager.kt` - Boundary tracking logic
  - `ChunkRepository.kt` - Loading/saving chunks
  - `ChunkedTextBuffer.kt` - High-level buffer operations

---

## Estimated Timeline

| Step | Description | Estimated Time |
|------|-------------|----------------|
| 1 | Update TextChunk data class | 5 min |
| 2 | Update ChunkManager helpers | 5 min |
| 3 | Update ChunkRepository.loadChunk() | 3 min |
| 4 | Update ChunkedTextBuffer.initialize() | 2 min |
| 5 | Update buildChunkMetadata() | 5 min |
| 6 | Update edit operations | 8 min |
| 7 | Update test files | 10-15 min |
| **Testing & Debugging** | Iterative fixes | 10-15 min |
| **Total** | | **30-45 min** |

---

## Questions & Answers

**Q: Why not keep both startOffset/endOffset AND boundaries?**
A: Dual source of truth leads to inconsistency bugs. After edits, chunks keep old offsets but boundaries are updated, causing failures in `buildChunkMetadata()`.

**Q: What if we just updated chunk offsets in updateBoundaries()?**
A: We tried this - it caused different issues because chunks should represent content, not file positions. Chunks can be evicted/reloaded, making stored positions unreliable.

**Q: How do we know this will fix all 14 failing tests?**
A: All 14 failures involve operations that call `buildChunkMetadata()`, which reads chunk offsets. Using boundaries instead should fix the root cause.

**Q: What's the long-term maintenance burden?**
A: Lower than current dual system. Single source of truth is easier to reason about and debug. Future developers will thank us.

---

## Success Criteria

Refactor is complete when:
1. ✅ All 142 tests passing
2. ✅ No compilation warnings
3. ✅ Code review approved
4. ✅ Performance within 10% of baseline
5. ✅ Documentation updated

---

**Last Updated**: 2025-10-31
**Author**: Option B Implementation Team
**Status**: Ready for implementation
