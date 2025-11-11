# Chunk Eviction Bugs - Fix Plan

**Status**: Not Started
**Priority**: P1 (affects data integrity and search accuracy)
**Created**: 2025-11-11
**Context**: All 6 bugs documented in commit `fa13fe718` (2025-10-30)

## Overview

The chunk eviction system has 6 known bugs that affect data integrity, search accuracy, and undo/redo operations. These bugs were discovered during cache pressure testing and intentionally documented with failing tests.

**Root Cause Area**: `ChunkManager.kt` lines 167-188 (eviction logic)

## Bug Summary

| # | Bug | Test | Severity | Impact |
|---|-----|------|----------|--------|
| 1 | Dirty chunks not protected | `dirty chunks are not evicted from cache()` | HIGH | Data loss |
| 2 | Edit after eviction fails | `editing evicted chunks reloads and modifies correctly()` | HIGH | Edit failures |
| 3 | Multi-chunk delete incomplete | `multi-chunk delete with cache pressure maintains integrity()` | HIGH | Data corruption |
| 4 | Save with evicted dirty chunks | `saving file with evicted dirty chunks works correctly()` | HIGH | Data loss |
| 5 | Search misses evicted results | `searching across evicted chunks works correctly()` | MEDIUM | Missing results |
| 6 | Undo with evicted chunks | `undo multi-chunk delete restores all content()` | MEDIUM | Undo failures |

## Detailed Bug Descriptions

### Bug #1: Dirty Chunks Not Protected from Eviction

**Test**: `dirty chunks are not evicted from cache()`
**Location**: `ChunkedTextBufferTest.kt:1645`

**Symptom**:
```
Expected: "AAAAAXXAAAAA"
Actual:   "AAAAAXXAAAAAAAA"
```

**Root Cause**:
- LRU cache evicts chunks without checking dirty state
- Modified chunks can be evicted before being saved
- Loss of user edits during cache pressure

**Expected Behavior**:
- Dirty chunks must NEVER be evicted
- Only clean chunks (persisted to disk) can be evicted
- Eviction should skip dirty chunks and find next LRU clean chunk

**Fix Strategy**:
1. Add `isDirty: Boolean` check to eviction logic
2. Filter eviction candidates to only clean chunks
3. If all chunks are dirty, trigger emergency flush or expand cache
4. Add cache metrics: dirty chunk count, eviction attempts blocked

### Bug #2: Edit After Eviction Fails

**Test**: `editing evicted chunks reloads and modifies correctly()`
**Location**: `ChunkedTextBufferTest.kt:1663`

**Symptom**:
- Editing content in evicted chunks throws exceptions or fails silently
- Chunk not reloaded from disk before modification

**Root Cause**:
- `ChunkManager.getChunk()` doesn't reload evicted chunks
- Boundaries exist but chunk data is missing from cache
- Edit operations assume chunk is always available

**Expected Behavior**:
- When chunk is evicted, boundaries remain but content is cleared
- Edit operation detects missing chunk and reloads from disk
- Modification proceeds normally after reload

**Fix Strategy**:
1. Add chunk reload logic in `getChunk()` when cache miss occurs
2. Check if chunk exists in repository before attempting reload
3. Mark reloaded chunk as dirty since it will be modified
4. Add metrics: chunk reload count, reload failures

### Bug #3: Multi-Chunk Delete with Cache Pressure

**Test**: `multi-chunk delete with cache pressure maintains integrity()`
**Location**: `ChunkedTextBufferTest.kt:1691`

**Symptom**:
- Deleting text spanning multiple chunks fails when some chunks are evicted
- Partial delete leaves content in inconsistent state
- Buffer length doesn't match actual content

**Root Cause**:
- Multi-chunk operations don't ensure all chunks are loaded
- Delete proceeds even when some chunks are evicted
- Boundaries updated but content not fully deleted

**Expected Behavior**:
- Before multi-chunk operation, ensure ALL affected chunks are loaded
- Lock all chunks involved in the operation (prevent eviction mid-operation)
- Complete operation atomically or rollback on failure

**Fix Strategy**:
1. Add `ensureChunksLoaded(chunkIds: Set<ChunkId>)` function
2. Call before any multi-chunk operation (delete, copy, move)
3. Temporarily mark chunks as "locked" during operation
4. Release locks after operation completes
5. Add operation-level locking separate from individual chunk access

### Bug #4: Save Fails with Evicted Dirty Chunks

**Test**: `saving file with evicted dirty chunks works correctly()`
**Location**: `ChunkedTextBufferTest.kt:1297`

**Symptom**:
- Save operation completes but dirty chunks are lost
- File contains stale data, not latest edits
- Silent data loss - no error reported to user

**Root Cause**:
- Save only persists chunks currently in cache
- Evicted dirty chunks (Bug #1) are skipped during save
- No validation that all dirty chunks were saved

**Expected Behavior**:
- Bug #1 fix (no dirty eviction) prevents this scenario
- Save validates all dirty chunks are in cache
- If dirty chunk evicted, fail save with error (should never happen after Bug #1 fix)

**Fix Strategy**:
1. Fix Bug #1 first (prevent dirty eviction)
2. Add save validation: check no dirty chunks are evicted
3. If validation fails, throw exception (indicates Bug #1 regression)
4. Add pre-save integrity check
5. Add metrics: dirty chunks at save time, validation failures

### Bug #5: Search Misses Results in Evicted Chunks

**Test**: `searching across evicted chunks works correctly()`
**Location**: `ChunkedTextBufferTest.kt:1728`

**Symptom**:
```
Expected 10 search results
Found only 7 results
```

**Root Cause**:
- Search only operates on chunks currently in cache
- Evicted chunks are skipped during search
- No chunk reload during search operation

**Expected Behavior**:
- Search loads evicted chunks on-demand during iteration
- All chunks searched regardless of cache state
- Results complete and accurate

**Fix Strategy**:
1. Modify search logic to use `ensureChunksLoaded()` for chunk range
2. For large files, implement streaming search (load chunks incrementally)
3. Add search progress indicator for large file searches
4. Consider keeping search results cached even when source chunks evicted
5. Add metrics: chunks loaded during search, search performance

### Bug #6: Undo with Evicted Chunks

**Test**: `undo multi-chunk delete restores all content()`
**Location**: `ChunkedTextBufferTest.kt:1755`

**Symptom**:
- Undo operation fails to restore content from evicted chunks
- Multi-chunk delete undo is incomplete
- Content partially restored

**Root Cause**:
- Undo history stores chunk IDs but not content
- When undo executes, referenced chunks may be evicted
- Undo can't restore data that's no longer in cache

**Expected Behavior**:
- Undo history stores actual content deltas, not just chunk references
- Undo can restore even if original chunks are evicted
- Undo creates new chunks with restored content if needed

**Fix Strategy**:
1. Change undo history to store content snapshots, not chunk IDs
2. For large operations, compress undo deltas
3. Consider undo history size limits (don't store full file copies)
4. Implement smart diffing for undo operations
5. Add metrics: undo history size, undo success rate

## Implementation Phases

### Phase 1: Critical Data Integrity (Bugs #1, #2, #4)
**Estimated Effort**: 3-5 days

1. **Bug #1: Prevent dirty chunk eviction**
   - Modify eviction logic to check `isDirty` state
   - Add dirty chunk tracking
   - Test: all 6 existing tests should improve

2. **Bug #2: Reload evicted chunks**
   - Add reload logic to `getChunk()`
   - Handle cache miss → disk load
   - Test: edit after eviction test passes

3. **Bug #4: Save validation**
   - Add pre-save validation
   - Verify no dirty chunks evicted
   - Test: save with eviction test passes

**Success Criteria**:
- Tests #1, #2, #4 pass
- No data loss under cache pressure
- Save always persists all edits

### Phase 2: Multi-Chunk Operations (Bug #3)
**Estimated Effort**: 2-3 days

1. **Chunk operation locking**
   - Implement `ensureChunksLoaded()`
   - Add operation-level locking
   - Prevent mid-operation eviction

2. **Multi-chunk atomicity**
   - Ensure all chunks loaded before operation
   - Complete or rollback pattern

**Success Criteria**:
- Test #3 passes
- Multi-chunk deletes always complete
- No partial operations

### Phase 3: Advanced Features (Bugs #5, #6)
**Estimated Effort**: 3-4 days

1. **Bug #5: Search with eviction**
   - Add chunk loading during search
   - Streaming search for large files
   - Progress indicator

2. **Bug #6: Undo history redesign**
   - Store content deltas not chunk IDs
   - Implement delta compression
   - Size limits on undo history

**Success Criteria**:
- Tests #5, #6 pass
- Search finds all results
- Undo works after eviction

### Phase 4: Polish and Monitoring
**Estimated Effort**: 1-2 days

1. **Add comprehensive metrics**
   - Cache hit/miss rates
   - Eviction counts (blocked vs successful)
   - Chunk reload operations
   - Dirty chunk count over time

2. **Performance testing**
   - Large file operations under cache pressure
   - Search performance with eviction
   - Undo/redo stress testing

3. **Documentation**
   - Update architecture docs
   - Document cache behavior
   - Add performance guidelines

**Success Criteria**:
- All 6 tests pass consistently
- Performance acceptable on large files
- Metrics show healthy cache behavior

## Testing Strategy

### Current Tests (Failing)
All 6 tests in `ChunkedTextBufferTest.kt` lines 1297-1800

### Additional Tests Needed

1. **Eviction Priority Tests**
   - Verify LRU order maintained
   - Dirty chunks never selected for eviction
   - Clean chunks evicted in correct order

2. **Cache Pressure Tests**
   - Sustained editing under max cache size
   - Large file operations (multi-GB)
   - Rapid chunk access patterns

3. **Edge Cases**
   - All chunks dirty (no eviction candidates)
   - Edit at chunk boundary with eviction
   - Concurrent operations during eviction
   - Save during active eviction

4. **Integration Tests**
   - Real-world file editing scenarios
   - Search and replace all under cache pressure
   - Extended undo/redo sequences
   - File save after hours of editing

### Performance Benchmarks
- Search 1GB file with 100MB cache
- Edit 500MB file with 50MB cache
- 1000 undo operations with eviction
- Save after 10,000 edits

## Risk Assessment

### High Risk Areas
1. **Undo History Redesign (Bug #6)**: Major architectural change, high complexity
2. **Multi-Chunk Locking (Bug #3)**: Potential deadlock risks
3. **Search Streaming (Bug #5)**: Performance implications for large files

### Low Risk Areas
1. **Dirty Chunk Protection (Bug #1)**: Simple flag check in eviction logic
2. **Chunk Reload (Bug #2)**: Straightforward cache miss handling
3. **Save Validation (Bug #4)**: Additional safety check

### Mitigation Strategies
- Implement high-risk changes last (after simpler fixes stabilize)
- Add extensive logging during development
- Feature flag for new eviction logic
- Gradual rollout with monitoring

## Success Metrics

### Functional Goals
- ✅ All 6 failing tests pass
- ✅ No data loss under any cache pressure
- ✅ Search finds 100% of results regardless of cache state
- ✅ Undo/redo works correctly after eviction

### Performance Goals
- Cache hit rate > 95% for normal editing
- Eviction overhead < 1ms per operation
- Search performance within 2x of no-eviction case
- Memory usage stays within configured limits

### Quality Goals
- Zero data loss bugs in production
- Comprehensive test coverage (>90%)
- Clear error messages for edge cases
- Monitoring dashboards for cache health

## Related Files

### Core Implementation
- `ChunkManager.kt` - Eviction logic (lines 167-188)
- `ChunkRepository.kt` - Disk persistence
- `ChunkedTextBuffer.kt` - Buffer operations
- `TextChunk.kt` - Chunk state management

### Tests
- `ChunkedTextBufferTest.kt` - 6 failing tests (lines 1297-1800)
- `ChunkedTextBufferConcurrencyTest.kt` - 8 passing concurrency tests

### Documentation
- `RACE_CONDITION_ANALYSIS.md` - Completed race condition fixes
- `CHUNK_EVICTION_BUGS_PLAN.md` - This file

## References

- **Original Bug Discovery**: Commit `fa13fe718` (2025-10-30)
- **Race Condition Fixes**: Commit `0f7c54d71` (2025-11-11)
- **Related Issue**: Fast typing race conditions (fixed)
- **Architecture**: LRU cache with chunked buffer design

## Next Steps

When ready to start implementation:

1. ✅ Read this plan thoroughly
2. ✅ Review all 6 failing tests to understand expected behavior
3. ✅ Read `ChunkManager.kt` eviction logic (lines 167-188)
4. ✅ Start with Phase 1 (Bug #1 - dirty chunk protection)
5. ✅ Implement fixes one at a time, running tests after each
6. ✅ Document any architectural changes discovered
7. ✅ Update this plan if new issues found

---

**Note**: This plan assumes the race condition fixes (commit `0f7c54d71`) are stable. If any regressions appear, address those first before tackling eviction bugs.
