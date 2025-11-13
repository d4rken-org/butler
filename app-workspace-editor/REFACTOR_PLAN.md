# Comprehensive Refactoring Plan: Text + Binary Editor Support

## Overview
Refactor the editor to support both text and binary (hex) modes using a hybrid architecture: **Sealed Classes (data layer) + Strategy Pattern (behavior layer)**. Use TDD for all new components and ensure existing tests continue to pass.

---

## Phase 1: Foundation - Sealed Classes & Core Types (3-4 days)

### 1.1 Create Sealed Class Hierarchy **[TDD]**

**New Files:**
- `EditorChunk.kt` - Sealed class replacing TextChunk
- `EditorChunkTest.kt` - Unit tests for chunk variants

**Test First (EditorChunkTest.kt):**
```kotlin
- Test EditorChunk.Text creation with line metadata
- Test EditorChunk.Binary creation without line metadata
- Test ByteArray equals/hashCode correctness
- Test chunk copy operations preserve types
- Test markDirty() for both variants
```

**Implementation:**
- Create `sealed class EditorChunk` with Text and Binary variants
- Text variant: content (String), lineCount, lineEnding
- Binary variant: content (ByteArray), custom equals/hashCode

**Refactor Existing:**
- Update `TextChunk.kt` → migrate to `EditorChunk.Text`
- Keep all existing fields and methods
- **Run all existing tests** - they should still pass with type alias

---

### 1.2 Create EditorMode Interface **[TDD]**

**New Files:**
- `EditorMode.kt` - Strategy interface
- `EditorModeType.kt` - Enum (TEXT, HEX)
- `EditorCapabilities.kt` - Capabilities data class
- `EditorModeTest.kt` - Interface contract tests

**Test First (EditorModeTest.kt):**
```kotlin
- Test mode type identification
- Test capabilities declaration
- Test loadChunk contract (mock implementations)
- Test saveChunk contract
```

**Implementation:**
- Define EditorMode interface with:
  - val type: EditorModeType
  - val capabilities: EditorCapabilities
  - suspend fun loadChunk(...): EditorChunk
  - suspend fun saveChunk(...)
  - fun createBuffer(...): EditorBuffer
  - @Composable fun RenderEditor(...)

---

### 1.3 Create TextMode (Wrap Existing Logic) **[TDD]**

**New Files:**
- `TextMode.kt` - Text mode strategy implementation
- `TextModeTest.kt` - Text mode specific tests

**Test First (TextModeTest.kt):**
```kotlin
- Test loadChunk returns EditorChunk.Text
- Test loadChunk decodes UTF-8 correctly
- Test loadChunk calculates line count
- Test loadChunk detects line endings (LF, CRLF, CR)
- Test saveChunk converts to UTF-8 bytes
- Test createBuffer returns ChunkedTextBuffer
- Test capabilities indicate text-specific features
```

**Implementation:**
- Move UTF-8 decode logic from FileDataSource to TextMode
- Move line counting logic to TextMode
- Move line ending detection to TextMode
- Keep ChunkedTextBuffer creation

**Verification:**
- Run all existing TextBuffer tests - should pass
- Run TextModeTest - new tests pass

---

### 1.4 Update ChunkManager for Sealed Classes **[Refactor + Test]**

**Modified Files:**
- `ChunkManager.kt` - Accept EditorChunk sealed class
- `ChunkManagerTest.kt` - Update tests for sealed class

**Test Updates:**
- Update all tests to use `EditorChunk.Text` instead of `TextChunk`
- Add tests for type-safe getters (getTextChunk, getBinaryChunk)
- Test LRU eviction works with both chunk types
- Test dirty tracking works with both chunk types

**Implementation Changes:**
- Change cache type: `Map<ChunkId, EditorChunk>`
- Update `addChunk`, `updateChunk`, `getChunk` signatures
- Add type-safe getters: `getTextChunk()`, `getBinaryChunk()`
- Keep mergeChunks algorithm (works with sealed class)

**Verification:**
- All existing ChunkManagerTest tests pass
- New type-safety tests pass

---

## Phase 2: Binary Infrastructure (4-5 days)

### 2.1 Create BinaryChunkRepository **[TDD]**

**New Files:**
- `BinaryChunkRepository.kt` - Loads binary chunks
- `BinaryChunkRepositoryTest.kt` - Binary loading tests

**Test First (BinaryChunkRepositoryTest.kt):**
```kotlin
- Test loadChunk reads raw bytes
- Test loadChunk preserves binary nulls (0x00)
- Test loadChunk handles all byte values (0x00-0xFF)
- Test loadChunk at chunk boundaries
- Test search for byte patterns
- Test search for hex strings
- Test saveFile writes raw bytes
```

**Implementation:**
- Similar to ChunkRepository but returns EditorChunk.Binary
- No UTF-8 conversion - keep as ByteArray
- Search operates on byte patterns

---

### 2.2 Create ChunkedBinaryBuffer **[TDD]**

**New Files:**
- `ChunkedBinaryBuffer.kt` - Binary buffer operations
- `ChunkedBinaryBufferTest.kt` - Binary buffer tests (mirror text tests)

**Test First (ChunkedBinaryBufferTest.kt):** *(Mirror ChunkedTextBuffer test structure)*
```kotlin
// Basic Operations
- Test insertBytes at start/middle/end
- Test deleteBytes single/multiple bytes
- Test replaceBytes
- Test getBytes for range

// Multi-Chunk Operations
- Test insert spanning 2 chunks
- Test delete spanning 3 chunks
- Test operations at exact chunk boundaries
- Test empty chunk handling

// Undo/Redo
- Test undo single byte operation
- Test undo multi-chunk operation
- Test redo operations
- Test undo stack limits

// Search
- Test search for byte pattern
- Test search for hex string (e.g., "DEADBEEF")
- Test search case sensitivity N/A
- Test search wrapping

// Concurrency
- Test concurrent byte modifications
- Test thread-safe operations

// Edge Cases
- Test binary nulls (0x00) preservation
- Test all byte values (0x00-0xFF)
- Test large binary files (100MB+)
```

**Implementation:**
- Byte-level cursor (not text position)
- Operations: insert/delete/replace bytes
- Undo/redo for byte operations
- Search for byte patterns
- No line concepts - offset-based only

**Verification:**
- All ChunkedBinaryBufferTest tests pass
- Performance test with 100MB binary file

---

### 2.3 Create HexMode **[TDD]**

**New Files:**
- `HexMode.kt` - Hex mode strategy
- `HexModeTest.kt` - Hex mode tests

**Test First (HexModeTest.kt):**
```kotlin
- Test loadChunk returns EditorChunk.Binary
- Test loadChunk preserves all bytes
- Test saveChunk writes raw bytes
- Test createBuffer returns ChunkedBinaryBuffer
- Test capabilities indicate hex-specific features
  (canGoToOffset=true, canGoToLine=false)
```

**Implementation:**
- loadChunk: read raw bytes, no conversion
- saveChunk: write raw bytes
- createBuffer: return ChunkedBinaryBuffer
- capabilities: offset-based, no line numbers

---

### 2.4 Create EditorBuffer Interface **[Refactor]**

**New Files:**
- `EditorBuffer.kt` - Common buffer interface
- Extract common operations from ChunkedTextBuffer

**Interface:**
```kotlin
interface EditorBuffer {
    val isModified: StateFlow<Boolean>
    suspend fun initialize(): Result<Unit>
    suspend fun saveFile(): Result<Unit>
    suspend fun undo(): Result<TextPosition?>
    suspend fun redo(): Result<TextPosition?>
    fun dispose()
}
```

**Refactor:**
- ChunkedTextBuffer implements EditorBuffer
- ChunkedBinaryBuffer implements EditorBuffer
- Common operations extracted to interface

---

## Phase 3: Integration & Mode Switching (3-4 days)

### 3.1 Update EditorDataSource for Modes **[Refactor]**

**Modified Files:**
- `EditorDataSource.kt` - Interface unchanged
- `FileDataSource.kt` - Remove UTF-8 conversion (delegate to mode)
- `FileDataSourceTest.kt` - Update tests
- `InMemoryDataSource.kt` - Support both text and binary

**Changes:**
- FileDataSource.readChunk() returns ByteArray (not String)
- Mode converts bytes → String or keeps as ByteArray
- InMemoryDataSource stores ByteArray, mode interprets

**Test Updates:**
```kotlin
- Test readChunk returns raw bytes
- Test save merges bytes correctly
- Test both text and binary content
```

---

### 3.2 Update EditorEngine for Modes **[TDD]**

**Modified Files:**
- `EditorEngine.kt` - Add mode support
- `EditorEngineTest.kt` - Add mode switching tests

**New Tests:**
```kotlin
- Test initialize with TextMode
- Test initialize with HexMode
- Test switchMode TEXT → HEX
- Test switchMode HEX → TEXT
- Test switchMode preserves cursor position (byte offset)
- Test mode-specific operations fail in wrong mode
- Test capabilities update on mode switch
```

**Implementation:**
- Add `mode: EditorMode` parameter
- Use mode.createBuffer() for buffer creation
- Use mode.loadChunk() via ChunkManager
- Add switchMode() function:
  - Save current position
  - Dispose old buffer
  - Create new buffer with new mode
  - Restore position (as byte offset)

**Verification:**
- All existing EditorEngine tests pass
- New mode switching tests pass

---

### 3.3 Create File Type Detection **[TDD]**

**New Files:**
- `FileAnalyzer.kt` - Detects binary vs text
- `FileAnalyzerTest.kt` - Detection tests

**Test First:**
```kotlin
- Test detect text files (UTF-8, ASCII)
- Test detect binary files (null bytes, high entropy)
- Test detect common binary formats (images, executables)
- Test ambiguous files (UTF-8 with some binary)
- Test empty files
- Test by extension (.txt, .bin, .exe, .png)
```

**Detection Logic:**
- Check first 8KB for null bytes (binary indicator)
- Validate UTF-8 decoding (text indicator)
- Check byte distribution (high entropy = binary)
- Fallback to file extension
- Return: TEXT, BINARY, AMBIGUOUS

---

### 3.4 Update EditorWorkspace for Mode Selection **[TDD]**

**Modified Files:**
- `EditorWorkspace.kt` - Add mode selection
- `EditorWorkspaceTest.kt` - Add mode tests (create if needed)

**New Tests:**
```kotlin
- Test openFile with text file → TextMode
- Test openFile with binary file → HexMode
- Test switchMode updates state
- Test mode persists in workspace state
- Test reopen file uses last mode
```

**Implementation:**
- Inject FileAnalyzer
- openFile():
  - Analyze file type
  - Choose mode (TEXT or HEX)
  - Create engine with mode
- Add switchMode(EditorModeType)
- Expose mode in EditorState

---

## Phase 4: UI Components (4-5 days)

### 4.1 Update EditorState for Modes **[Refactor]**

**Modified Files:**
- `EditorState.kt` - Add mode fields

**New Fields:**
```kotlin
val mode: EditorModeType?
val capabilities: EditorCapabilities?
val binaryState: BinaryEditorState? // For hex view
```

---

### 4.2 Create LazyHexEditor Composable **[TDD with Previews]**

**New Files:**
- `LazyHexEditor.kt` - Hex editor UI
- Previews for hex editor states

**UI Components:**
```kotlin
@Composable
fun LazyHexEditor(
    buffer: ChunkedBinaryBuffer,
    cursor: Long,
    selection: LongRange?,
    bytesPerRow: Int = 16,
    onByteChange: (offset: Long, bytes: ByteArray) -> Unit,
    onCursorMove: (Long) -> Unit,
    modifier: Modifier = Modifier
)
```

**Features:**
- Three-column layout: Address | Hex | ASCII
- Virtualized scrolling (LazyColumn)
- Address column (8-digit hex)
- Hex column (16 bytes per row, space every 8)
- ASCII column (printable chars, . for non-printable)
- Cursor highlighting
- Selection highlighting
- Byte editing (click to edit hex value)

**Previews:**
```kotlin
@Preview2 fun LazyHexEditorPreview()
@Preview2 fun LazyHexEditorWithSelectionPreview()
@Preview2 fun LazyHexEditorEmptyPreview()
```

---

### 4.3 Create Hex Editor Toolbar **[Composable]**

**New Composables:**
- `HexEditorToolbar.kt` - Hex-specific toolbar
- Go to offset dialog
- Bytes per row selector
- Data inspector panel (show selected bytes as int16/32, float, etc.)

---

### 4.4 Update EditorWorkspacePage for Modes **[Refactor]**

**Modified Files:**
- `EditorWorkspacePage.kt` - Render based on mode

**Changes:**
- Add mode selector dropdown (TEXT / HEX)
- Conditional rendering:
  - When TEXT → LazyTextEditor
  - When HEX → LazyHexEditor
- Show mode-appropriate toolbar
- Disable incompatible actions (e.g., "Go to Line" in hex mode)

---

### 4.5 Update EditorWorkspaceViewModel **[Refactor]**

**Modified Files:**
- `EditorWorkspaceViewModel.kt` - Add mode actions

**New Actions:**
```kotlin
fun onModeSwitch(newMode: EditorModeType)
fun onGoToOffset(offset: Long) // For hex mode
fun onInsertBytes(offset: Long, bytes: ByteArray)
```

---

## Phase 5: Advanced Features (3-4 days)

### 5.1 Hex Editor Search **[TDD]**

**Implementation:**
- Search for hex patterns (e.g., "DEADBEEF")
- Search for ASCII strings in binary data
- Find all occurrences
- Navigate between results

**Tests:**
```kotlin
- Test search hex pattern
- Test search ASCII in binary
- Test search wrapping
- Test search performance on large files
```

---

### 5.2 Hex Editor Bookmarks **[Feature]**

**New Files:**
- `HexBookmark.kt` - Bookmark data class
- `HexBookmarkManager.kt` - Manage bookmarks
- `HexBookmarksPanel.kt` - UI for bookmarks

**Features:**
- Add bookmark at offset
- Name bookmarks
- Jump to bookmark
- List all bookmarks
- Persist bookmarks in workspace state

---

### 5.3 Data Inspector **[Feature]**

**New Composable:**
- `DataInspectorPanel.kt` - Show selected bytes as various types

**Display:**
- Hex value
- Decimal (uint8, int8, uint16, int16, uint32, int32)
- Float (float32, float64)
- ASCII string
- UTF-8 string
- Little/Big endian toggle

---

## Phase 6: Testing & Edge Cases (2-3 days)

### 6.1 Integration Tests

**New Test Files:**
- `EditorModeSwitchingIntegrationTest.kt`
- `TextToBinaryRoundTripTest.kt`
- `LargeFilePerformanceTest.kt`

**Test Scenarios:**
```kotlin
- Open text file, switch to hex, switch back → data preserved
- Edit in text mode, switch to hex → edits visible as bytes
- Edit in hex mode, switch to text → edits visible as text
- Open 100MB binary file → loads in reasonable time
- Rapid mode switching → no crashes
```

---

### 6.2 Edge Case Tests

**New Tests:**
- Binary files with all byte values (0x00-0xFF)
- Files with mixed UTF-8 and binary data
- Zero-byte files
- Files with only null bytes
- UTF-16/UTF-32 encoded files (show as binary)
- Corrupted UTF-8 files (fallback to hex)

---

### 6.3 Existing Test Migration

**Process:**
- Ensure all existing tests still pass
- Update test names if needed (TextChunk → EditorChunk.Text)
- Add type assertions where helpful
- No behavioral changes

---

## Phase 7: Localization & Polish (1-2 days)

### 7.1 String Extraction

**Files to Update:**
- `app-workspace-editor/src/main/res/values/strings.xml`

**New Strings:**
```xml
<string name="editor_mode_text">Text</string>
<string name="editor_mode_hex">Hex</string>
<string name="editor_switch_mode_action">Switch Mode</string>
<string name="editor_goto_offset_action">Go to Offset…</string>
<string name="editor_bytes_per_row">Bytes per Row</string>
<string name="editor_data_inspector">Data Inspector</string>
<string name="editor_bookmark_add_action">Add Bookmark</string>
<string name="editor_binary_file_detected">Binary file detected - opened in hex mode</string>
```

---

### 7.2 Composable Previews

**Requirement:**
- All new composables must have `@Preview2` functions
- Multiple preview scenarios:
  - Empty state
  - With data
  - With selection
  - Error states

---

## Test Execution Strategy

### TDD Workflow (Red-Green-Refactor):
1. **Red**: Write failing test for new functionality
2. **Green**: Implement minimum code to pass test
3. **Refactor**: Clean up implementation
4. **Verify**: Run all tests (new + existing)

### Test Execution Order:
```bash
# After each phase, run:
./gradlew :app-workspace-editor:testDebugUnitTest

# Run specific test class:
./gradlew :app-workspace-editor:testDebugUnitTest --tests "EditorChunkTest"

# Run all tests continuously during development:
./gradlew :app-workspace-editor:testDebugUnitTest --continuous
```

---

## File Structure Summary

### New Files (~25):
- EditorChunk.kt + Test
- EditorMode.kt + related types
- TextMode.kt + Test
- HexMode.kt + Test
- BinaryChunkRepository.kt + Test
- ChunkedBinaryBuffer.kt + Test
- EditorBuffer.kt (interface)
- FileAnalyzer.kt + Test
- LazyHexEditor.kt
- HexEditorToolbar.kt
- HexBookmark.kt + Manager
- DataInspectorPanel.kt
- Integration tests (3-4 files)

### Modified Files (~10):
- TextChunk.kt → EditorChunk.kt
- ChunkManager.kt
- EditorEngine.kt
- EditorDataSource.kt
- FileDataSource.kt
- InMemoryDataSource.kt
- EditorWorkspace.kt
- EditorWorkspacePage.kt
- EditorWorkspaceViewModel.kt
- EditorState.kt
- All corresponding test files

---

## Success Criteria

✅ All existing tests pass (50+ tests)
✅ All new tests pass (80+ new tests)
✅ Can open text files in text mode
✅ Can open binary files in hex mode
✅ Can switch between modes preserving data
✅ Hex editor displays all byte values correctly
✅ Search works in both modes
✅ Undo/redo works in both modes
✅ Large files (100MB+) load and perform well
✅ UI shows mode-appropriate controls
✅ All strings localized
✅ All composables have previews

---

## Timeline: ~18-24 days total

- Phase 1: 3-4 days
- Phase 2: 4-5 days
- Phase 3: 3-4 days
- Phase 4: 4-5 days
- Phase 5: 3-4 days
- Phase 6: 2-3 days
- Phase 7: 1-2 days

## Risk Mitigation

**Risk**: Existing tests break during refactoring
**Mitigation**: Run tests after each file change, use type aliases during transition

**Risk**: Performance issues with large binary files
**Mitigation**: Profile early (Phase 2), optimize chunk size, add performance tests

**Risk**: Complex mode switching bugs
**Mitigation**: Extensive integration tests (Phase 6), careful state management

---

## Architecture Diagrams

### Current Architecture (Text Only)
```
User opens file
    ↓
FileDataSource reads bytes → String (UTF-8)
    ↓
ChunkRepository creates TextChunk(content: String)
    ↓
ChunkManager caches TextChunk
    ↓
ChunkedTextBuffer provides text operations
    ↓
EditorEngine orchestrates
    ↓
LazyTextEditor renders text
```

### New Architecture (Text + Binary)
```
User opens file
    ↓
FileAnalyzer detects type → TEXT or BINARY
    ↓
EditorWorkspace creates EditorEngine with appropriate EditorMode
    ↓
EditorEngine creates ChunkManager(dataSource, mode)
    ↓
ChunkManager.loadChunk() → calls mode.loadChunk()
    ↓
    ├─ TextMode.loadChunk() → EditorChunk.Text(content: String)
    └─ HexMode.loadChunk() → EditorChunk.Binary(content: ByteArray)
    ↓
ChunkManager caches EditorChunk (sealed class)
    ↓
EditorEngine creates Buffer via mode.createBuffer(chunkManager)
    ↓
    ├─ TextMode.createBuffer() → ChunkedTextBuffer
    └─ HexMode.createBuffer() → ChunkedBinaryBuffer
    ↓
UI calls mode.RenderEditor()
    ↓
    ├─ TextMode.RenderEditor() → LazyTextEditor
    └─ HexMode.RenderEditor() → LazyHexEditor
```

### Sealed Class Structure
```kotlin
sealed class EditorChunk {
    abstract val offset: Long
    abstract val size: Long
    abstract val isDirty: Boolean
    abstract fun markDirty(): EditorChunk

    data class Text(
        override val offset: Long,
        val content: String,        // String for text
        override val size: Long,
        val lineCount: Int,         // Text-specific
        val lineEnding: LineEnding, // Text-specific
        override val isDirty: Boolean
    ) : EditorChunk()

    data class Binary(
        override val offset: Long,
        val content: ByteArray,     // ByteArray for binary
        override val size: Long,
        override val isDirty: Boolean
    ) : EditorChunk() {
        // Custom equals/hashCode for ByteArray
    }
}
```

### Strategy Pattern Structure
```kotlin
interface EditorMode {
    val type: EditorModeType
    val capabilities: EditorCapabilities

    suspend fun loadChunk(
        dataSource: DataSource,
        offset: Long,
        size: Long
    ): EditorChunk

    suspend fun saveChunk(
        dataSource: DataSource,
        chunk: EditorChunk
    )

    fun createBuffer(chunkManager: ChunkManager): EditorBuffer

    @Composable
    fun RenderEditor(
        buffer: EditorBuffer,
        state: EditorState,
        onAction: (EditorAction) -> Unit,
        modifier: Modifier
    )
}
```

---

## Implementation Notes

### Key Design Decisions

1. **Why Sealed Classes for Chunks?**
   - Type-safe data storage
   - Compiler-enforced exhaustive checking
   - Each variant has exactly the fields it needs
   - Easy serialization

2. **Why Strategy Pattern for Modes?**
   - Clean separation of text vs hex behavior
   - Easy to add new modes later (image viewer, log viewer, etc.)
   - Mode-specific logic isolated
   - UI delegates rendering to mode

3. **Why Keep Line Metadata in Text Chunks?**
   - Performance optimization for line-based operations
   - Avoids scanning content for every "go to line" operation
   - Cached once, used many times
   - Only relevant for text mode

4. **Why DataSource Returns ByteArray?**
   - Mode interprets bytes (text vs binary)
   - No assumptions at I/O layer
   - Supports both UTF-8 and binary data
   - Clean separation of concerns

### Future Extensibility

This architecture makes it easy to add:
- **Image Viewer Mode**: Display images inline with metadata
- **JSON/XML Mode**: Tree view with syntax highlighting
- **Log Viewer Mode**: Structured log parsing with filtering
- **Archive Viewer Mode**: Browse zip/tar contents
- **CSV Mode**: Tabular view with column operations

Each new mode:
1. Implements `EditorMode` interface
2. Defines its own chunk type (or reuses existing)
3. Creates mode-specific buffer and UI
4. No changes to core infrastructure

---

Ready to start implementation!
