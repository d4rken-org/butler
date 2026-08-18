package eu.darken.butler.editor.core.engine

import eu.darken.butler.common.files.write.FileCommitContext
import eu.darken.butler.editor.core.engine.text.BlockIndexBuilder
import eu.darken.butler.editor.core.sources.EditorDataSource
import eu.darken.butler.editor.core.sources.InMemoryDataSource
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import okio.Source
import org.junit.jupiter.api.Test
import java.io.FileNotFoundException

/**
 * The verified-mutation primitive: [DocumentBuffer.applyMutation] must be all-or-nothing. Every
 * patch is checked against the live document (and the caller's expected version) BEFORE the first
 * mutation, so a request built from a stale snapshot leaves the document exactly as it was.
 */
class DocumentBufferMutationTest : DocumentBufferTestBase() {

    private fun patch(startOffset: Long, oldText: String, newText: String) = DocumentBuffer.VerifiedPatch(
        startOffset = startOffset,
        endOffset = startOffset + oldText.length,
        expectedOldText = oldText,
        newText = newText,
    )

    private suspend fun DocumentBuffer.text(): String = getText(0, totalLength.value).getOrThrow()

    /** A tiny undo budget floors the threshold at [DocumentBuffer.MIN_UNDOABLE_EDIT_CHARS]. */
    private suspend fun createFlooredBuffer(content: String) =
        createBuffer(content, maxUndoMemoryBytes = 100)

    @Test
    fun `a version mismatch fails as a stale match without mutating`() = runTest {
        val buffer = createBuffer("Hello World")
        val version = buffer.getStructuralVersion()

        val result = buffer.applyMutation(
            expectedVersion = version + 1,
            patches = listOf(patch(6L, "World", "Kotlin")),
            undoPolicy = DocumentBuffer.UndoPolicy.SEPARATE,
        )

        result.exceptionOrNull().shouldBeInstanceOf<StaleMatchException>()
        buffer.text() shouldBe "Hello World"
        buffer.getStructuralVersion() shouldBe version
    }

    @Test
    fun `an old-text mismatch fails as a stale match without mutating`() = runTest {
        val buffer = createBuffer("Hello World")

        val result = buffer.applyMutation(
            expectedVersion = null,
            patches = listOf(patch(6L, "Warld", "Kotlin")),
            undoPolicy = DocumentBuffer.UndoPolicy.SEPARATE,
        )

        result.exceptionOrNull().shouldBeInstanceOf<StaleMatchException>()
        buffer.text() shouldBe "Hello World"
    }

    @Test
    fun `a range that does not match its old text is refused`() = runTest {
        // The invariant endOffset == startOffset + expectedOldText.length is the caller's contract;
        // violating it must fail cleanly instead of splicing a mismatched range.
        val buffer = createBuffer("Hello World")

        val result = buffer.applyMutation(
            expectedVersion = null,
            patches = listOf(
                DocumentBuffer.VerifiedPatch(
                    startOffset = 6L,
                    endOffset = 11L,
                    expectedOldText = "Wor",
                    newText = "Kotlin",
                ),
            ),
            undoPolicy = DocumentBuffer.UndoPolicy.SEPARATE,
        )

        result.exceptionOrNull().shouldBeInstanceOf<IllegalArgumentException>()
        buffer.text() shouldBe "Hello World"
    }

    @Test
    fun `overlapping patches are refused before anything mutates`() = runTest {
        val buffer = createBuffer("abcdefgh")

        val result = buffer.applyMutation(
            expectedVersion = null,
            patches = listOf(patch(0L, "abcd", "X"), patch(2L, "cdef", "Y")),
            undoPolicy = DocumentBuffer.UndoPolicy.SEPARATE,
        )

        result.exceptionOrNull().shouldBeInstanceOf<StaleMatchException>()
        buffer.text() shouldBe "abcdefgh"
    }

    @Test
    fun `multi-patch application is back-to-front, matching replaceMatches`() = runTest {
        val viaMutation = createBuffer("one two three")
        viaMutation.applyMutation(
            expectedVersion = null,
            patches = listOf(patch(0L, "one", "1"), patch(4L, "two", "2"), patch(8L, "three", "3")),
            undoPolicy = DocumentBuffer.UndoPolicy.SEPARATE,
        ).getOrThrow()

        val viaReplaceMatches = createBuffer("one two three")
        viaReplaceMatches.replaceMatches(
            listOf(
                DocumentBuffer.MatchReplacement(0L, "one", "1"),
                DocumentBuffer.MatchReplacement(4L, "two", "2"),
                DocumentBuffer.MatchReplacement(8L, "three", "3"),
            ),
        ).getOrThrow()

        viaMutation.text() shouldBe "1 2 3"
        viaMutation.text() shouldBe viaReplaceMatches.text()
        // One undo step for the whole batch, on both paths
        viaMutation.undo().getOrThrow()
        viaMutation.text() shouldBe "one two three"
        viaReplaceMatches.undo().getOrThrow()
        viaReplaceMatches.text() shouldBe "one two three"
    }

    @Test
    fun `COALESCE merges keystroke patches into one typing run`() = runTest {
        val buffer = createBuffer("")

        for ((index, char) in "abc".withIndex()) {
            buffer.applyMutation(
                expectedVersion = null,
                patches = listOf(patch(index.toLong(), "", char.toString())),
                undoPolicy = DocumentBuffer.UndoPolicy.COALESCE,
            ).getOrThrow()
        }

        buffer.text() shouldBe "abc"
        buffer.undo().getOrThrow()
        // The whole run steps back at once, not one character at a time
        buffer.text() shouldBe ""
        buffer.canUndo() shouldBe false
    }

    @Test
    fun `SEPARATE keeps each mutation its own undo step`() = runTest {
        val buffer = createBuffer("")

        for ((index, char) in "abc".withIndex()) {
            buffer.applyMutation(
                expectedVersion = null,
                patches = listOf(patch(index.toLong(), "", char.toString())),
                undoPolicy = DocumentBuffer.UndoPolicy.SEPARATE,
            ).getOrThrow()
        }

        buffer.undo().getOrThrow()
        buffer.text() shouldBe "ab"
        buffer.undo().getOrThrow()
        buffer.text() shouldBe "a"
    }

    @Test
    fun `a delete+insert patch is one undo step`() = runTest {
        val buffer = createBuffer("teh quick")

        buffer.applyMutation(
            expectedVersion = null,
            patches = listOf(patch(1L, "eh", "he")),
            undoPolicy = DocumentBuffer.UndoPolicy.COALESCE,
        ).getOrThrow()

        buffer.text() shouldBe "the quick"
        buffer.undo().getOrThrow()
        buffer.text() shouldBe "teh quick"
        buffer.canUndo() shouldBe false
    }

    @Test
    fun `the returned version is the one the document reached`() = runTest {
        val buffer = createBuffer("Hello")

        val outcome = buffer.applyMutation(
            expectedVersion = buffer.getStructuralVersion(),
            patches = listOf(patch(5L, "", " World")),
            undoPolicy = DocumentBuffer.UndoPolicy.SEPARATE,
        ).getOrThrow()

        outcome.newVersion shouldBe buffer.getStructuralVersion()
        outcome.undoable shouldBe true
        // Chaining on it succeeds, which is what a keystroke burst relies on
        buffer.applyMutation(
            expectedVersion = outcome.newVersion,
            patches = listOf(patch(11L, "", "!")),
            undoPolicy = DocumentBuffer.UndoPolicy.SEPARATE,
        ).isSuccess shouldBe true
        buffer.text() shouldBe "Hello World!"
    }

    @Test
    fun `a patch beyond the undoable budget is refused instead of materialized`() = runTest {
        // Recorded patches always materialize their old text; the oversized path is a separate API
        val oversized = DocumentBuffer.MIN_UNDOABLE_EDIT_CHARS.toInt() + 1
        val buffer = createFlooredBuffer("A".repeat(oversized))

        val result = buffer.applyMutation(
            expectedVersion = null,
            patches = listOf(patch(0L, "A".repeat(oversized), "")),
            undoPolicy = DocumentBuffer.UndoPolicy.SEPARATE,
        )

        result.exceptionOrNull().shouldBeInstanceOf<IllegalArgumentException>()
        buffer.totalLength.value shouldBe oversized.toLong()
    }

    // ==================== Oversized (unrecorded) replace ====================

    @Test
    fun `applyOversizedReplace is version-checked`() = runTest {
        val oversized = DocumentBuffer.MIN_UNDOABLE_EDIT_CHARS.toInt() + 1
        val buffer = createFlooredBuffer("A".repeat(oversized))
        val stale = buffer.getStructuralVersion() - 1

        val result = buffer.applyOversizedReplace(
            expectedVersion = stale,
            startOffset = 0L,
            endOffset = oversized.toLong(),
            newText = "x",
        )

        result.exceptionOrNull().shouldBeInstanceOf<StaleMatchException>()
        buffer.totalLength.value shouldBe oversized.toLong()
    }

    @Test
    fun `applyOversizedReplace clears history and raises the unrecorded-edit flag`() = runTest {
        val oversized = DocumentBuffer.MIN_UNDOABLE_EDIT_CHARS.toInt() + 1
        val buffer = createFlooredBuffer("A".repeat(oversized))
        buffer.insertText(TextPosition(0L, 0, 0), "seed").getOrThrow()
        buffer.canUndo() shouldBe true

        val outcome = buffer.applyOversizedReplace(
            expectedVersion = buffer.getStructuralVersion(),
            startOffset = 0L,
            endOffset = buffer.totalLength.value,
            newText = "x",
        ).getOrThrow()

        buffer.text() shouldBe "x"
        outcome.undoable shouldBe false
        outcome.newVersion shouldBe buffer.getStructuralVersion()
        buffer.canUndo() shouldBe false
        buffer.nonUndoableEditPending.first() shouldBe true
    }

    @Test
    fun `an out-of-bounds oversized replace is refused without mutating`() = runTest {
        val buffer = createBuffer("Hello")

        val result = buffer.applyOversizedReplace(
            expectedVersion = buffer.getStructuralVersion(),
            startOffset = 0L,
            endOffset = 500L,
            newText = "x",
        )

        result.exceptionOrNull().shouldBeInstanceOf<IllegalArgumentException>()
        buffer.text() shouldBe "Hello"
    }

    // ==================== Versioned replace (explicit-intent edits) ====================

    @Test
    fun `applyVersionedReplace refuses a stale version without mutating`() = runTest {
        val buffer = createBuffer("Hello World")
        val version = buffer.getStructuralVersion()

        val result = buffer.applyVersionedReplace(
            expectedVersion = version + 1,
            startOffset = 6L,
            endOffset = 11L,
            newText = "Kotlin",
        )

        result.exceptionOrNull().shouldBeInstanceOf<StaleMatchException>()
        buffer.text() shouldBe "Hello World"
        buffer.getStructuralVersion() shouldBe version
    }

    @Test
    fun `applyVersionedReplace returns exactly the span it removed`() = runTest {
        // Single materialization: the caller's removed text IS the undo entry's text, never a
        // second read of the (already mutated) document
        val buffer = createBuffer("Hello World")
        val expected = buffer.getText(6L, 11L).getOrThrow()

        val (outcome, removed) = buffer.applyVersionedReplace(
            expectedVersion = buffer.getStructuralVersion(),
            startOffset = 6L,
            endOffset = 11L,
            newText = "Kotlin",
        ).getOrThrow()

        removed shouldBe expected
        removed shouldBe "World"
        buffer.text() shouldBe "Hello Kotlin"
        outcome.newVersion shouldBe buffer.getStructuralVersion()
        outcome.undoable shouldBe true
    }

    @Test
    fun `a versioned replace over a selection is ONE undo step`() = runTest {
        // Deliberate change: the old delete-then-insert recorded two entries, so undoing a paste
        // over a selection restored the selected text only on the SECOND undo
        val buffer = createBuffer("Hello World")

        buffer.applyVersionedReplace(
            expectedVersion = buffer.getStructuralVersion(),
            startOffset = 6L,
            endOffset = 11L,
            newText = "Kotlin",
        ).getOrThrow()

        buffer.undo().getOrThrow()
        buffer.text() shouldBe "Hello World"
        buffer.canUndo() shouldBe false

        buffer.redo().getOrThrow()
        buffer.text() shouldBe "Hello Kotlin"
    }

    @Test
    fun `a versioned insert and delete each record their narrowest operation`() = runTest {
        val buffer = createBuffer("Hello")

        buffer.applyVersionedReplace(
            expectedVersion = buffer.getStructuralVersion(),
            startOffset = 5L,
            endOffset = 5L,
            newText = " World",
        ).getOrThrow().second shouldBe ""
        buffer.text() shouldBe "Hello World"

        val (_, removed) = buffer.applyVersionedReplace(
            expectedVersion = buffer.getStructuralVersion(),
            startOffset = 0L,
            endOffset = 6L,
            newText = "",
        ).getOrThrow()
        removed shouldBe "Hello "
        buffer.text() shouldBe "World"

        buffer.undo().getOrThrow()
        buffer.text() shouldBe "Hello World"
        buffer.undo().getOrThrow()
        buffer.text() shouldBe "Hello"
    }

    @Test
    fun `a versioned replace beyond the undoable budget is refused instead of materialized`() = runTest {
        val oversized = DocumentBuffer.MIN_UNDOABLE_EDIT_CHARS.toInt() + 1
        val buffer = createFlooredBuffer("A".repeat(oversized))

        val result = buffer.applyVersionedReplace(
            expectedVersion = buffer.getStructuralVersion(),
            startOffset = 0L,
            endOffset = oversized.toLong(),
            newText = "x",
        )

        result.exceptionOrNull().shouldBeInstanceOf<IllegalArgumentException>()
        buffer.totalLength.value shouldBe oversized.toLong()
    }

    @Test
    fun `an out-of-bounds versioned replace is refused without mutating`() = runTest {
        val buffer = createBuffer("Hello")

        val result = buffer.applyVersionedReplace(
            expectedVersion = buffer.getStructuralVersion(),
            startOffset = 0L,
            endOffset = 500L,
            newText = "x",
        )

        result.exceptionOrNull().shouldBeInstanceOf<IllegalArgumentException>()
        buffer.text() shouldBe "Hello"
    }

    @Test
    fun `a failure mid-splice rolls the document back`() = runTest {
        val source = BreakableSource(InMemoryDataSource(workspaceId, "Hello World").also { it.open() })
        val buffer = DocumentBuffer(
            workspaceId = workspaceId,
            dataSource = source,
            maxUndoStackSize = 100,
            maxUndoMemoryBytes = 10_485_760,
            blockSize = BlockIndexBuilder.DEFAULT_BLOCK_SIZE,
            assertions = true,
        )
        buffer.initialize().getOrThrow()
        // Warm the decode cache so the removed-span read succeeds and the failure lands on the
        // splice itself (its offset mapping reads the backing bytes directly)
        buffer.text() shouldBe "Hello World"
        source.failWith = { FileNotFoundException("open failed: ENOENT (No such file or directory)") }

        val result = buffer.applyVersionedReplace(
            expectedVersion = buffer.getStructuralVersion(),
            startOffset = 6L,
            endOffset = 11L,
            newText = "Kotlin",
        )

        result.isFailure shouldBe true
        source.failWith = null
        buffer.text() shouldBe "Hello World"
        buffer.canUndo() shouldBe false
    }

    // ==================== Atomic read ====================

    @Test
    fun `getTextWithVersion pairs the slice with the version it was read at`() = runTest {
        val buffer = createBuffer("Hello World")

        val (text, version) = buffer.getTextWithVersion(0L, 5L).getOrThrow()

        text shouldBe "Hello"
        version shouldBe buffer.getStructuralVersion()
        // The pairing is what makes it usable as an identity: replaying it against the moved
        // document must be rejected, never applied to a same-looking range
        buffer.applyVersionedReplace(version, 0L, 5L, "Hi").isSuccess shouldBe true
        buffer.applyVersionedReplace(version, 0L, 2L, "Yo")
            .exceptionOrNull().shouldBeInstanceOf<StaleMatchException>()
    }

    /** Fails byte reads on demand, so a splice can be broken after the document loaded. */
    private class BreakableSource(private val delegate: EditorDataSource) : EditorDataSource {
        var failWith: (() -> Throwable)? = null
        override val contentSource: StateFlow<ContentSource> = delegate.contentSource
        override suspend fun open() = delegate.open()
        override suspend fun getSize(): Long = delegate.getSize()
        override suspend fun close() = delegate.close()
        override suspend fun getMeta(): EditorDataSource.Meta {
            failWith?.let { throw it() }
            return delegate.getMeta()
        }

        override suspend fun openByteSource(offset: Long): Source {
            failWith?.let { throw it() }
            return delegate.openByteSource(offset)
        }

        override suspend fun commit(writer: suspend (FileCommitContext) -> Unit) =
            delegate.commit(writer)
    }
}
