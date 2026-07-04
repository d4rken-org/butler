# Editor v3: piece-table engine rewrite — implementation plan

Status: **approved design, not yet started** (2026-07-04). This document is self-contained: it carries everything an implementing agent needs, including context that is not otherwise written down.

## Why

An architecture review (2026-07-04) found the chunk engine in `app-workspace-editor` structurally unsound and **empirically verified data corruption** with scratch tests (10-char blocks, 100-char in-memory doc, `ChunkedTextBufferTestBase`-style setup):

1. **Save corrupts multi-chunk files.** `ChunkManager.updateBoundaries()` shifts all `ChunkBoundary`s to post-edit logical coordinates after every edit, but `ChunkManager.mergeChunks()` (called from `FileDataSource.save`/`InMemoryDataSource.save`) merges dirty chunks into the *pre-edit original file bytes* using those *post-edit* boundaries. Verified: insert "XYZ" at offset 5 in a 10-chunk doc → 3 bytes silently lost after chunk 1; delete range [2,7) → deleted bytes resurface. Single-chunk files round-trip fine, which is why existing tests (all content < 64KB default chunk size) never caught it. `mergeChunks` unit tests pass hand-built *original* boundaries — a contract production never fulfills.
2. **Reads corrupt after eviction, no save needed.** After a single-chunk edit, later clean chunks stay evictable (cache = 5 chunks); reloading one reads the *unedited* file at the *shifted* boundary. Verified: insert then `getText(0, totalLength)` returned 53 of 103 chars. Multi-chunk *deletes* work around this by marking every chunk after the edit dirty (`ChunkedTextBuffer.kt` multi-chunk delete path) — which loads the rest of the file into memory, defeating the design; single-chunk edits have no protection.
3. **Save path breaks large-file goals**: reads the whole original file into memory, merges via boxed `mutableListOf<Byte>`, truncates offsets with `.toInt()` (>2GB corrupts silently). Auto-save (`EditorWorkspace`, `editorSettings.autoSaveInterval`) makes corruption user-triggerable by typing in a >64KB file.
4. **Byte/char conflation**: `TextChunk.size` is UTF-16 chars, boundaries are byte offsets; `ChunkManager.loadChunk` mixes them (`contentBasedEndOffset = boundary.startOffset + chunk.size`). The runtime surrogate-pair "flexible chunk size" heuristics (5% tolerance, EOF-gap 1–4 bytes, neighbor absorption + eviction) are symptomatic patches.

Root cause: **three coordinate systems** (file byte offsets, UTF-16 char offsets, post-edit logical offsets) stored in the same `ChunkBoundary` fields with no type distinction, and a cache that is authoritative for dirty content so eviction is correctness-critical.

**Decisions already made by the user (do not re-litigate):**
- Rewrite the engine core as a **piece table** (options considered and rejected: patching the chunk design with dual extents — keeps mutable chunks authoritative; rope over fully-loaded content — abandons the large-file use case).
- The paused "#4 dual-offset refactor" (worktree `editor-offset-model`, branch `worktree-editor-offset-model`, WIP commit `9ef5a2a21`, plan `~/.claude/plans/swirling-tinkering-curry.md`) is **superseded**. Its `ChunkedTextBufferMultibyteTest.kt` scenarios are ported here (see Phase 2); the worktree is retired after this lands. Do not resume its phases.
- `isModified` save-checkpoint semantics: **in scope** (undo back to saved state clears the flag).
- Add-buffer compaction: **in scope** (16MB threshold).
- Plan was reviewed by Codex (gpt-5.5, two rounds); all feedback below is already integrated.

## Target design

The document is an ordered sequence of **pieces**, each referencing one of two immutable stores. Edits split pieces and insert new ones; content is never mutated. There is no `updateBoundaries()`, no dirty-marking, no pinning.

### PieceTable
- `Piece.Original`: `byteStart/byteLen` (logical post-BOM file coordinates, **immutable after load**) + `charStart/charCount/lineBreakCount` cached at load. `Piece.Added`: `addStart/addLen` char offsets into an append-only add buffer (`StringBuilder`).
- v1 storage: flat list + lazily rebuilt prefix sums (`charStarts`, `lineBreakStarts`); O(pieces) per edit is acceptable; keep storage `internal`/encapsulated so a balanced tree (VS Code-style) can replace it later without API change. Coalesce consecutive typing inserts (new insert at the add buffer's tail extending the previous Added piece).
- **Surrogate policy**: original-piece splits snap to code-point boundaries — if an edit offset lands between a surrogate pair's halves inside an Original piece, materialize that code point into the add buffer (internal structure changes; document content and all public offsets unchanged). Edit offsets remain arbitrary UTF-16 code-unit positions (parity with today); a mid-pair *insert* legitimately produces lone surrogates, which re-encode as U+FFFD on save — documented, matches current behavior. At save, **contiguous Added runs are encoded as one string** so pairs re-joined by edits encode correctly.
- **CRLF across pieces**: per-piece `lineBreakCount` counts universal breaks (`\r\n|\n|\r`) within the piece; pieces carry `startsWithLf`/`endsWithCr` flags and aggregation subtracts CRLF joins at piece seams (a doc of pieces `"\r"` + `"\n"` is ONE break). All line APIs and `totalLines` must account for this.
- **Add-buffer compaction**: when the add buffer exceeds 16MB with a smaller live footprint, rebuild it from live Added pieces under the buffer mutex, remapping `addStart`. Text-based undo ops unaffected. WARN-log each compaction.
- Debug invariant (assertions flag: on in tests and debug builds): `sum(piece charCounts) == totalCharLength`, line-break totals (with CRLF-join correction) consistent — checked after every edit.

### BlockIndex + BlockIndexBuilder (load)
One sequential streaming scan at open (progress-reported via the existing `Progress.Data` + `editor_progress_opening` strings, cancellable via `ensureActive()` per block) producing per-~64KB-block records `(byteStart, byteLen, charCount, lineBreakCount)`:
- **Block edges snapped to code-point boundaries**: drive a single stateful `CharsetDecoder` with `endOfInput=false`; on underflow, carry remainder bytes to the next block; **also carry a trailing high surrogate at the decoded-char layer** (the decoder can still emit output ending mid-pair, esp. UTF-16 + small buffers). `endOfInput=true` only at true EOF (file ending mid-sequence → replacement char, consistent counts). Never snap by manual byte inspection.
- Whole-file line-ending counters (crlf/lf/cr with pending-`\r` carry across edges) → `LineEnding` detection over the whole file (better than today's 3-chunk sample).
- **BOM**: detect once (reuse logic extracted from `FileDataSource.detectCharsetFromBOM`/`isValidUTF8` into `CharsetDetector`); ALL index byte offsets are logical/post-BOM; `bomSize` re-added in exactly two places: the decode-cache block loader and the save splice.
- **Malformed input policy**: decode with `CodingErrorAction.REPLACE`. Untouched Original pieces save byte-verbatim (malformed bytes preserved); edits touching replacement chars re-encode U+FFFD at that spot only.

### DecodeCache
LRU (~16 blocks) of decoded Original blocks, `LinkedHashMap(accessOrder=true)` + `Mutex`. **Pure cache, never authoritative** — edits live in the add buffer + piece list, so eviction is always safe. The entire pin/refcount/dirty machinery of the old engine is deleted, not ported.

### Save = streaming splice (DocumentBuffer.saveFile, under the buffer mutex)
Cancellation honored while writing the temp/backup (abort = cleanup, original untouched); `NonCancellable` only from the point of no return (target replacement/restore) through rebase + error-state transition.
1. **Staleness check (best-effort, document as such)**: compare current physical size + mtime against values captured at open/last rebase, PLUS re-hash of the first and last Original blocks (guards coarse/missing mtime on SAF/root and same-size edits). On mismatch → fail with an explicit "file changed externally" error, no splice. Not race-free — same limitation as any file editor.
2. `dataSource.commit { sink -> … }`: write BOM once (manually from stored `bomBytes`; UTF-16 encoders normalized to `UTF_16LE`/`UTF_16BE` so they never emit BOMs), then walk pieces in document order using **positional per-piece reads** (`okio.FileHandle.read(fileOffset, …)` — positional reads are supported on ALL gateway backends: local, SAF via ParcelFileDescriptorFileHandle, root/IPC; do NOT depend on a forward-only stream), copying Original byte ranges **verbatim** (no decode/re-encode), encoding contiguous Added runs with `detectedCharset`.
3. **Original-bytes stability during commit**: backup-swap mode (LocalPath) writes to a temp first — the original file stays readable throughout. In-place mode (SAF) overwrites the target, so the writer must read Original ranges **from the backup copy** that `commitViaInPlace` creates before overwriting; the `commit` contract exposes which source the writer reads from.
4. **Post-save rebase (REQUIRED)**: rescan the saved file → new `BlockIndex`; `pieceTable.reset()` to one Original piece; clear the decode cache; capture new size/mtime/hashes. `isModified` clears only after rebase succeeds. If commit succeeded but rebase fails → buffer enters an error state requiring reload (never serve reads from stale pieces).
- **`commit(writer)` contract** (document + test): original content readable (per mode) until writer completes; sink flushed/closed by commit; backup restored on writer failure; no partial target left; handles closed. Reuse `FileDataSource.commitViaBackupSwap`/`commitViaInPlace` semantics as-is; `writeContent` becomes writer-lambda based; the in-place backup becomes a streaming copy (no `originalBytes: ByteArray`).

### Undo/redo + isModified checkpoint
Text-based `EditOperation` stacks with the existing memory caps (`estimateMemoryBytes`, evict-oldest, keep ≥1) — they survive the post-save rebase because char content is identical across it. **Save checkpoint**: each edit gets a monotonic generation; ops record the generation they produce; save records the current generation. `isModified = currentGeneration != savedGeneration`, recomputed on undo/redo. Invalidate the saved generation (modified-until-next-save) when the op carrying it is evicted by memory caps or its redo region is discarded. Do NOT use an undo-stack *index* as the save point (fragile under coalescing/trimming).

### Search
`WindowedSearch` — sliding decoded window over the logical document. Base window 64KB; literal/whole-word queries: overlap = `query.length − 1` (min 4KB), growing the window to `max(64KB, 2 × query.length)` so long literals are never missed. Regex: 4KB overlap; matches longer than the overlap are a documented limitation. Zero-length regex matches are skipped and the scan advances ≥1 code unit (no dupes/infinite loops at window edges). Accept matches starting before `windowEnd − overlap` (except the final window). Track running line count/last-line-start while sliding so results carry absolute line/column in one pass. Absorb `buildSearchPattern` from `ChunkRepository` (escape/wholeWord/regex logic unchanged).

### Offsets
All public offsets are **char offsets (UTF-16 code units, `Long`)**. Byte offsets exist only inside Original pieces / `BlockIndex` / save path. No `.toInt()` on document offsets anywhere.

### totalLines semantics (preserve exactly)
`breaks + (endsWithBreak ? 0 : 1)`, min 1 — `"a\n"` is 1 line, empty doc is 1 line. Pinned by ported black-box tests; any divergence is investigated, not adjusted.

## New files

Package `eu.darken.butler.editor.core.engine.text` — **pure JVM, no Android/Hilt deps** (only stdlib, coroutines, okio, `java.nio.charset`):
- `Piece.kt`, `PieceTable.kt`
- `OriginalDocument.kt` — interface: `readChars(charStart, charEnd)`, `charToByte(charOffset)`, `countLineBreaks(range)`, `findNthLineBreak(range, n)`; plus `charLength`, `byteLength`, `lineBreakCount`
- `BlockIndex.kt`, `BlockIndexBuilder.kt` (with `DEFAULT_BLOCK_SIZE = 64 * 1024`)
- `CharsetDetection.kt` (`CharsetDetector.detect(sample): CharsetDetection(charset, bomBytes)`)
- `TextMetrics.kt` (relocated `detectLineEnding`/`countLines` + universal break-scan with CR-carry + char→byte-in-block helper)
- `DecodeCache.kt`, `BlockOriginalDocument.kt` (OriginalDocument over BlockIndex + DecodeCache; the loader closure adds `bomSize`)
- `WindowedSearch.kt`

Package `eu.darken.butler.editor.core.engine`:
- `DocumentBuffer.kt` — replaces the interior of `ChunkedTextBuffer` with the **same public surface**: StateFlows `contentSource/lineEnding/totalLines/totalLength/isModified`; methods `initialize{onProgress}/release/getText/getFullText/getTextForLine/getTextForRange/insertText/deleteText/replaceText/findPosition/findOffset/search/saveFile/undo/redo/canUndo/canRedo`. Assisted-injected: `create(workspaceId, dataSource, maxUndoStackSize, maxUndoMemoryBytes, blockSize, assertions)` (undo settings from `EditorSettings.undoStackSize/undoMaxMemory` as today; `assertions = BuildConfig.DEBUG` from the engine, `true` in tests). ONE mutex around every op (each edit/query, and the whole save incl. rebase).
- Relocated unchanged, same package (no consumer import changes): `LineEnding`, `TextPosition`, `EditOperation` (currently in `TextChunk.kt`). `SearchResult` → own file, **drop the `chunkId` field** — only used in `ui/editor/elements/EditorSearchBar.kt` preview constructors (two sites) — fix those.

Line lookup implementation: line L start = binary search over line-break prefix sums (CRLF-join-corrected) → piece containing the L-th break → `findNthLineBreak` within it (Added: scan add buffer; Original: narrow via `BlockIndex.blockForLineBreak`); read forward to next break across pieces; strip one trailing `\r` (parity with current `getTextForLine`).

## Data source redesign (`eu.darken.butler.editor.core.sources`)

Final `EditorDataSource`:
```kotlin
interface EditorDataSource {
    val contentSource: StateFlow<ContentSource>
    suspend fun open()
    suspend fun getSize(): Long                                   // physical bytes incl. BOM
    suspend fun getMeta(): Meta                                   // size + mtime for staleness check
    suspend fun openByteSource(offset: Long = 0L): Source         // positional; caller closes
    suspend fun commit(writer: suspend (BufferedSink) -> Unit)    // atomic full-content replace, contract above
    suspend fun close()
}
```
Removed: `readChunk`, chunk-shaped `save(List<TextChunk>, Map<ChunkId, ChunkBoundary>)`, `openSource`, `isModified` (buffer owns it).
- `FileDataSource`: keeps charset detection (delegating to `CharsetDetector`), keeps `commitViaBackupSwap`/`commitViaInPlace` semantics and unique artifact naming; `openByteSource(offset)` = `gatewaySwitch.file(path, readWrite=false)` + `FileHandle.source(offset)` (source that closes the handle on close). Note `canWrite` is currently hardcoded `true` — leave as-is (out of scope).
- `InMemoryDataSource`: stays (tests + scratch buffers); UTF-8 byte view for reads, commit collects the sink into a Buffer and decodes UTF-8 back into `content`; `setContent` stays.

## Wiring

- `EditorResources` → `(dataSource, textBuffer)` (drop `chunkRepository`/`chunkManager`).
- `EditorEngine`: drop chunk factories, inject `DocumentBuffer.Factory`; `textBuffer` property type changes; **all call sites unchanged** (verified: the engine drives the full public API; `EditorWorkspace`/ViewModel/UI use only `TextPosition`/`SearchResult`/`SearchOptions`/`ContentSource` in signatures).
- No module outside `app-workspace-editor` uses engine types except a debug-only screenshot file importing `ContentSource` (unchanged).
- `EditorSettings`: untouched (only undo + auto-save settings exist; there is no chunk/cache setting).

## Deleted (Phase 4)

`ChunkManager.kt`, `ChunkRepository.kt`, `ChunkBoundary.kt`, `TextChunk.kt` (after relocations), `ChunkedTextBuffer.kt`; tests `ChunkManagerTest`, `ChunkRepositoryTest`, all 11 `ChunkedTextBuffer*Test` + `ChunkedTextBufferTestBase` (after porting).

## Phases

Work in a fresh git worktree off local main (EnterWorktree). One commit per phase; every phase gates on `./gradlew :app-workspace-editor:testDebugUnitTest` green (run via the `devtools:build-runner` agent to keep output out of context). Ported black-box tests are **copies with assertions unchanged** — any divergence is investigated, not adjusted.

**Phase 1 — pure core (`engine/text/`), old engine untouched.** All new classes + JVM tests:
- `PieceTableTest`: splits at every boundary class (piece start/middle/end), cross-piece delete, whole-doc delete, empty doc, coalescing, surrogate-boundary splits (materialize-to-add), CRLF split/join across pieces (insert/delete between `\r` and `\n`, delete one side), compaction (large paste + delete → compact → undo → redo → full-text compare; offsets remapped), invariants after every op. Use both a trivial in-memory `OriginalDocument` fake and `BlockOriginalDocument` over byte arrays.
- `BlockIndexBuilderTest`: `"中文中文中文"` @ blockSize=8 (3-byte chars force snapping every block); 4-byte emoji straddling an edge (assert no block ends in a high surrogate — byte AND char-layer carry); UTF-16LE/BE ± BOM with tiny decode buffers; BOM-only file; file ending mid-sequence (REPLACE policy); CRLF split across an edge counts once; MIXED detection; empty file.
- `BlockOriginalDocumentTest`: `charToByte`/`readChars`/`findNthLineBreak` round-trips on mixed-width content (`"aé中\nx中é"`); fake source with offsets > `Int.MAX_VALUE` (truncation guard).
- `DecodeCacheTest` (LRU, loader counting, concurrency), `WindowedSearchTest` (window-edge and overlap-spanning matches, literal overlap ≥ needle length incl. very long literals, zero-width regex at edges, dedupe, case/wholeWord/regex options, multibyte offsets).

**Phase 2 — reads + DocumentBuffer (no save).** Add `openByteSource`/`getMeta` ALONGSIDE the old interface methods (old engine keeps compiling/working). Relocate `LineEnding`/`TextPosition`/`EditOperation`. Implement `DocumentBuffer` fully except `saveFile` (returns failure). New `DocumentBufferTestBase.createBuffer(content: String, blockSize: Int = DEFAULT_BLOCK_SIZE)`. Port all 11 black-box `ChunkedTextBuffer*Test` → `DocumentBuffer*Test` (the old `CacheTest` becomes observable-behavior-only). Port the multibyte scenarios from the old worktree's `ChunkedTextBufferMultibyteTest` (file-backed, small blocks): CJK whole-doc read, boundary splitting a 3-byte char, findOffset/findPosition round-trip on mixed-width, insert after multibyte (`"中文"` + "X" at offset 1 → `"中X文"`), delete multibyte by char range, BOM+multibyte+CRLF, `totalLength == getFullText().length` invariant, multibyte search offsets. Add the corruption reproducer: **whole-doc read-back after multi-block edit without save** (fails on old engine — must pass). CRLF-across-pieces tests for `getTextForLine`/`findOffset`/`findPosition`/`totalLines`. Stress sanity: thousands of small edits → line lookup/search timings logged (no formal gate). Spot-check positional reads on SAF/root early (fallback: sequential `source().skip()`).

**Phase 3 — streaming save + rebase.** Add `commit(writer)`; implement splice, staleness check, cancellation/point-of-no-return semantics, post-save rebase, save checkpoint. `DocumentBufferSaveTest`:
- multi-block insert→save and delete→save with **exact on-disk bytes** (both fail on the old engine); save→evict→reread; BOM+multibyte+CRLF exact bytes; BOM multi-dirty-region exact bytes; UTF-16 save with multiple Added runs (BOM written once, LE/BE encoder, Original ranges verbatim); malformed-bytes file: untouched region round-trips byte-verbatim.
- failure semantics: writer throws mid-splice (backup restored, buffer consistent, isModified stays true); rebase failure after successful commit → error state; cancellation before point of no return → clean abort, original untouched, buffer editable; cancellation after → NonCancellable completes; external modification (size, mtime, or sampled-hash mismatch) → explicit error, no splice; in-place (SAF-style) commit reading Original ranges from the backup (fake data source forcing in-place mode).
- checkpoint: save → edit → undo to save point ⇒ `isModified=false`; redo away ⇒ true; save point evicted/discarded ⇒ stays true; save→edit→undo-past-save→save.
- empty-file save; no-op skip when `!isModified`.

**Phase 4 — wiring + deletion.** Switch `EditorEngine`/`EditorResources`/`EditorState` to `DocumentBuffer`; drop `chunkId` from `SearchResult` + fix `EditorSearchBar` previews; strip old methods from `EditorDataSource` + implementations; delete old classes/tests; rework `FileDataSourceTest`/`FileDataSourceEncodingTest` to the new interface (detection assertions partly move to `CharsetDetector`/`BlockIndexBuilder` tests; commit-path tests keep backup-swap/in-place assertions incl. writer-failure restore). Gate additionally on `:app:compileFossDebugKotlin`.

**Phase 5 — integration + sweep.** Update `EditorEngine*Test` setups (ReplaceText, Selection, SelectionEdit, SearchInvalidation, ContentStream). Add engine-level cases: type→save→type→undo-past-save; goToLine/getTextForRange on a >2-block multibyte doc; search invalidation after edits; `getContentStream` byte-exactness with BOM. Grep for dead chunk references (`TextChunk`, `ChunkBoundary`, `chunkId`) and stale KDoc (e.g. the `EditorDataSource` header). **Codex implementation review before commit/merge** (paste diffs inline — the Codex MCP here cannot read the working tree reliably). After merge: retire the `editor-offset-model` worktree (branch `worktree-editor-offset-model`) and update the project memory files that reference it.

## Risks / non-goals

Risks: positional `FileHandle` reads on SAF/root (spot-check Phase 2; fallback sequential skip) · O(pieces) prefix-sum cost (bounded by coalescing + compaction; storage swappable) · line-count semantic drift (pinned by ported tests before deletion) · Phase 4 breadth (mechanical: Phases 2–3 keep both engines compiling side by side) · save failure matrix (explicit Phase 3 tests).

Non-goals: balanced-tree piece storage; legacy single-byte encodings beyond today's behavior; incremental index build during save (post-save rescan is v1); regex matches longer than the search overlap; UI/ViewModel/autosave/settings changes; API renames beyond `ChunkedTextBuffer → DocumentBuffer` and dropping `SearchResult.chunkId`. `getFullText`/`getContentStream` (Save As path in `EditorWorkspace.saveFileAs`) still materialize the full document — unchanged, documented tradeoff.

## Verification

- Per phase: `./gradlew :app-workspace-editor:testDebugUnitTest` (via build-runner agent); Phase 4 also `:app:compileFossDebugKotlin`.
- The three corruption reproducers (multi-block insert→save, delete→save, read-after-edit-no-save) demonstrably fail on the old engine and must pass on the new one.
- End-to-end smoke on the butler-main emulator (emulator-5562): open a >1MB file, edit near the top, scroll to bottom (evict/reload path), save, reopen, verify content; repeat with a UTF-8 CJK file and auto-save enabled; modify the file externally via adb while open, attempt save, verify the explicit staleness error.

## Process notes for the implementing agent

- Follow repo conventions: `.claude/rules/coding-standards.md` (minimal comments, trailing commas), `technical-patterns.md` (logging via `log(tag)`, Kotlin types), `commit-guidelines.md` (`Editor:` prefix, user-friendly titles).
- Use a worktree; run builds/tests through the `devtools:build-runner` agent; have Codex review the implementation before committing significant changes; add tests and verify before merge.
- Consumer-surface and gateway-capability claims in this document were verified against the code on 2026-07-04; re-verify anything that looks stale before relying on it.
