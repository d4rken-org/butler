package eu.darken.butler.editor.core.engine

import eu.darken.butler.editor.core.sources.EditorDataSource
import eu.darken.butler.editor.core.sources.InMemoryDataSource
import eu.darken.butler.workspace.core.Workspace
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.spyk
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import okio.Source
import org.junit.jupiter.api.Test
import testhelpers.coroutine.TestDispatcherProvider
import java.io.FileNotFoundException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random
import kotlin.uuid.Uuid

/**
 * [EditorEngine.performEdit]: the engine resolves the intent's target itself, under its own lock,
 * against the live cursor and selection - so an edit that waited in the queue acts on the document
 * as it IS, or not at all.
 */
class EditorEngineEditIntentTest : EditorEngineTestBase() {

    // ==================== Per-intent happy paths ====================

    @Test
    fun `InsertAtCursor inserts at the cursor and leaves it after the insert`() = runTest {
        val engine = createEngine("Hello World")
        engine.setCursorPosition(TextPosition(offset = 5, line = 0, column = 5))

        engine.performInsert(" there").shouldBeInstanceOf<EditorEngine.EditOutcome.Applied>().removedText shouldBe ""

        engine.fullContent() shouldBe "Hello there World"
        engine.cursorPosition.value.offset shouldBe 11L
        engine.selectionRange.value shouldBe null
    }

    @Test
    fun `DeleteSelection removes the selection and reports what it removed`() = runTest {
        val engine = createEngine("Hello World")
        engine.setSelection(pos(0, 6).copy(offset = 6), pos(0, 11).copy(offset = 11))

        engine.performDeleteSelection()
            .shouldBeInstanceOf<EditorEngine.EditOutcome.Applied>().removedText shouldBe "World"

        engine.fullContent() shouldBe "Hello "
        engine.cursorPosition.value.offset shouldBe 6L
        engine.selectionRange.value shouldBe null
    }

    @Test
    fun `DeleteForward removes the char after the cursor and keeps the cursor where it was`() = runTest {
        val engine = createEngine("Hello World")
        engine.setCursorPosition(TextPosition(offset = 5, line = 0, column = 5))

        engine.performDeleteForward()
            .shouldBeInstanceOf<EditorEngine.EditOutcome.Applied>().removedText shouldBe " "

        engine.fullContent() shouldBe "HelloWorld"
        engine.cursorPosition.value.offset shouldBe 5L
    }

    @Test
    fun `DeleteForward at the document end is a no-op`() = runTest {
        val engine = createEngine("Hello")
        engine.setCursorPosition(TextPosition(offset = 5, line = 0, column = 5))

        engine.performDeleteForward()
            .shouldBeInstanceOf<EditorEngine.EditOutcome.Applied>().removedText shouldBe ""

        engine.fullContent() shouldBe "Hello"
        engine.canUndo.first() shouldBe false
    }

    @Test
    fun `DeleteSelection without a selection fails instead of guessing a target`() = runTest {
        val engine = createEngine("Hello World")
        engine.setCursorPosition(TextPosition(offset = 5, line = 0, column = 5))

        engine.performDeleteSelection()
            .shouldBeInstanceOf<EditorEngine.EditOutcome.Failed>()
            .error.shouldBeInstanceOf<IllegalStateException>()

        engine.fullContent() shouldBe "Hello World"
    }

    // ==================== Selection resolution ====================

    @Test
    fun `every intent consumes the selection first`() = runTest {
        for (intent in listOf(EditorEngine.EditIntent.DeleteSelection, EditorEngine.EditIntent.DeleteForward)) {
            val engine = createEngine("Hello World")
            engine.setSelection(
                TextPosition(offset = 0, line = 0, column = 0),
                TextPosition(offset = 5, line = 0, column = 5),
            )

            engine.performEdit(intent, engine.currentEpoch)
                .shouldBeInstanceOf<EditorEngine.EditOutcome.Applied>().removedText shouldBe "Hello"

            engine.fullContent() shouldBe " World"
            engine.cursorPosition.value.offset shouldBe 0L
        }
    }

    @Test
    fun `a reversed selection is normalized before it is resolved`() = runTest {
        val engine = createEngine("Hello World")
        engine.setSelection(
            TextPosition(offset = 11, line = 0, column = 11),
            TextPosition(offset = 6, line = 0, column = 6),
        )

        engine.performInsert("Kotlin").shouldBeInstanceOf<EditorEngine.EditOutcome.Applied>()

        engine.fullContent() shouldBe "Hello Kotlin"
        // End of the inserted text, not the reversed range's stored end
        engine.cursorPosition.value.offset shouldBe 12L
    }

    @Test
    fun `a collapsed selection inserts at its offset instead of at the cursor`() = runTest {
        val engine = createEngine("Hello World")
        engine.setCursorPosition(TextPosition(offset = 11, line = 0, column = 11))
        engine.setSelection(
            TextPosition(offset = 5, line = 0, column = 5),
            TextPosition(offset = 5, line = 0, column = 5),
        )

        engine.performInsert("!").shouldBeInstanceOf<EditorEngine.EditOutcome.Applied>()

        engine.fullContent() shouldBe "Hello! World"
        engine.cursorPosition.value.offset shouldBe 6L
        engine.selectionRange.value shouldBe null
    }

    @Test
    fun `a selection spanning a CRLF break deletes both units`() = runTest {
        val engine = createEngine("a\r\nb\r\nc")
        engine.setSelection(
            TextPosition(offset = 1, line = 0, column = 1),
            TextPosition(offset = 4, line = 1, column = 1),
        )

        engine.performDeleteSelection()
            .shouldBeInstanceOf<EditorEngine.EditOutcome.Applied>().removedText shouldBe "\r\nb"

        engine.fullContent() shouldBe "a\r\nc"
        engine.totalLines.value shouldBe 2L
    }

    // ==================== Undo semantics ====================

    @Test
    fun `inserting over a selection is ONE undo step`() = runTest {
        // Deliberate change: the old delete-then-insert recorded two entries, so the first undo
        // after a paste over a selection left neither the old nor the new text
        val engine = createEngine("Hello World")
        engine.setSelection(
            TextPosition(offset = 6, line = 0, column = 6),
            TextPosition(offset = 11, line = 0, column = 11),
        )

        engine.performInsert("Kotlin").shouldBeInstanceOf<EditorEngine.EditOutcome.Applied>()
        engine.fullContent() shouldBe "Hello Kotlin"

        engine.undo().getOrThrow()
        engine.fullContent() shouldBe "Hello World"
        engine.canUndo.first() shouldBe false

        engine.redo().getOrThrow()
        engine.fullContent() shouldBe "Hello Kotlin"
    }

    // ==================== Oversized gating ====================

    private val threshold = DocumentBuffer.MIN_UNDOABLE_EDIT_CHARS.toInt()

    @Test
    fun `a selection of exactly the threshold applies directly`() = runTest {
        val engine = createEngine("A".repeat(threshold), undoMaxMemoryBytes = 100)
        engine.selectAll()

        engine.performDeleteSelection().shouldBeInstanceOf<EditorEngine.EditOutcome.Applied>()

        engine.fullContent() shouldBe ""
        engine.canUndo.first() shouldBe true
    }

    @Test
    fun `one char over the threshold is gated and mutates nothing`() = runTest {
        val engine = createEngine("A".repeat(threshold + 1), undoMaxMemoryBytes = 100)
        engine.selectAll()

        val gate = engine.performInsert("x")
            .shouldBeInstanceOf<EditorEngine.EditOutcome.RequiresConfirmation>()

        gate.prepared.startOffset shouldBe 0L
        gate.prepared.endOffset shouldBe (threshold + 1).toLong()
        gate.prepared.replacement shouldBe "x"
        engine.fullContent() shouldBe "A".repeat(threshold + 1)
    }

    @Test
    fun `a gated edit confirmed against a moved document mutates nothing`() = runTest {
        val engine = createEngine("A".repeat(threshold + 1), undoMaxMemoryBytes = 100)
        engine.selectAll()
        val gate = engine.performDeleteSelection()
            .shouldBeInstanceOf<EditorEngine.EditOutcome.RequiresConfirmation>()

        // Something else edited while the confirmation dialog was up
        engine.setCursorPosition(TextPosition(offset = 0, line = 0, column = 0))
        engine.performInsert("Z").shouldBeInstanceOf<EditorEngine.EditOutcome.Applied>()

        engine.submitPrepared(gate.prepared).shouldBeInstanceOf<EditorEngine.MutationResult.Conflict>()

        engine.fullContent() shouldBe "Z" + "A".repeat(threshold + 1)
    }

    // ==================== Epoch and editability rejections ====================

    @Test
    fun `an intent stamped for another document is dropped without a banner`() = runTest {
        val engine = createEngine("Hello World")
        engine.setCursorPosition(TextPosition(offset = 0, line = 0, column = 0))

        engine.performEdit(EditorEngine.EditIntent.InsertAtCursor("X"), Uuid.random())
            .shouldBeInstanceOf<EditorEngine.EditOutcome.Failed>()
            .error.shouldBeInstanceOf<StaleMatchException>()

        engine.fullContent() shouldBe "Hello World"
        engine.error.value shouldBe null
    }

    @Test
    fun `a document that went read-only mid-session refuses every intent`() = runTest {
        // The backing file vanished: the buffer latches read-only, and the engine's editability
        // gate refuses further edits up front instead of failing them one by one
        val engine = createBreakableEngine("Hello World")
        // Mid-document: the splice has to map char offsets onto the original bytes, which is where
        // the vanished file first shows up (an append at EOF needs no original read at all)
        engine.setCursorPosition(TextPosition(offset = 5, line = 0, column = 5))
        breakableSource.failWith = { FileNotFoundException("open failed: ENOENT (No such file or directory)") }

        engine.performInsert("X")
        engine.textBuffer!!.isBackingLost.value shouldBe true

        engine.performInsert("Y").shouldBeInstanceOf<EditorEngine.EditOutcome.Failed>()
            .error.shouldBeInstanceOf<BackingUnavailableException>()
        engine.performDeleteForward().shouldBeInstanceOf<EditorEngine.EditOutcome.Failed>()
            .error.shouldBeInstanceOf<BackingUnavailableException>()

        breakableSource.failWith = null
        engine.fullContent() shouldBe "Hello World"
    }

    // ==================== Interleaving ====================

    @Test
    fun `a replacement landing between resolution and apply makes the edit retry`() = runTest {
        // Search-and-replace commits OUTSIDE the engine's stateMutex, so it can move the document
        // between the version read and the splice. The edit must re-resolve, not be dropped.
        val interleaveOnce = AtomicBoolean(true)
        val engine = createSpiedEngine("Hello World") { buffer ->
            coEvery { buffer.getStructuralVersion() } coAnswers {
                val version = callOriginal()
                if (interleaveOnce.compareAndSet(true, false)) {
                    buffer.replaceMatches(listOf(DocumentBuffer.MatchReplacement(0L, "Hello", "Howdy")))
                }
                version
            }
        }
        engine.setCursorPosition(TextPosition(offset = 11, line = 0, column = 11))

        engine.performInsert("!").shouldBeInstanceOf<EditorEngine.EditOutcome.Applied>()

        interleaveOnce.get() shouldBe false
        engine.fullContent() shouldBe "Howdy World!"
    }

    // ==================== Harnesses ====================

    /** Wraps the engine's data source so its byte reads can be broken after the document loaded. */
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

        override suspend fun commit(writer: suspend (EditorDataSource.CommitContext) -> Unit) =
            delegate.commit(writer)
    }

    private lateinit var breakableSource: BreakableSource

    private suspend fun createBreakableEngine(content: String): EditorEngine =
        createCustomEngine(content) { dataSource ->
            DocumentBuffer(
                workspaceId = workspaceId,
                dataSource = BreakableSource(dataSource).also { breakableSource = it },
                maxUndoStackSize = 100,
                maxUndoMemoryBytes = 10 * 1_048_576L,
                blockSize = 1024,
                assertions = true,
            )
        }

    /** Buffer spy seam: [stub] can hook a buffer call to mutate the document from the outside. */
    private suspend fun createSpiedEngine(
        content: String,
        stub: (DocumentBuffer) -> Unit,
    ): EditorEngine = createCustomEngine(content) { dataSource ->
        spyk(
            DocumentBuffer(
                workspaceId = workspaceId,
                dataSource = dataSource,
                maxUndoStackSize = 100,
                maxUndoMemoryBytes = 10 * 1_048_576L,
                blockSize = 1024,
                assertions = true,
            ),
        ).also(stub)
    }

    private suspend fun createCustomEngine(
        content: String,
        bufferFactory: (EditorDataSource) -> DocumentBuffer,
    ): EditorEngine {
        val engine = EditorEngine(
            workspaceId = workspaceId,
            filePath = null,
            initialContent = content,
            gatewaySwitch = mockk(),
            editorSettings = createMockSettings(),
            dispatcherProvider = TestDispatcherProvider(),
            fileDataSourceFactory = mockk(),
            inMemoryDataSourceFactory = object : InMemoryDataSource.Factory {
                override fun create(workspaceId: Workspace.Id, initialContent: String) =
                    InMemoryDataSource(workspaceId, initialContent)
            },
            documentBufferFactory = object : DocumentBuffer.Factory {
                override fun create(
                    workspaceId: Workspace.Id,
                    dataSource: EditorDataSource,
                    maxUndoStackSize: Int,
                    maxUndoMemoryBytes: Long,
                    blockSize: Int,
                    assertions: Boolean,
                    staleSampleRandom: Random,
                    timeSource: kotlin.time.TimeSource,
                    maxDisplayLineChars: Int,
                ) = bufferFactory(dataSource)
            },
        )
        engine.initialize().getOrThrow()
        return engine
    }
}
