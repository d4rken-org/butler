package eu.darken.butler.editor.core.engine

import eu.darken.butler.common.datastore.DataStoreValue
import eu.darken.butler.common.files.write.FileCommitContext
import eu.darken.butler.editor.core.EditorSettings
import eu.darken.butler.editor.core.sources.EditorDataSource
import eu.darken.butler.editor.core.sources.InMemoryDataSource
import eu.darken.butler.workspace.core.Workspace
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldNotBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import okio.Source
import org.junit.jupiter.api.Test
import java.io.FileNotFoundException
import kotlin.random.Random
import testhelpers.coroutine.TestDispatcherProvider

/**
 * copySelection with a size cap: the refusal happens under the engine lock BEFORE the selection
 * is materialized, so oversized copies never allocate the selection text.
 */
class EditorEngineCopySelectionTest : DocumentBufferTestBase() {

    private fun createMockSettings(): EditorSettings {
        val settings = mockk<EditorSettings>()
        val undoStackSize = mockk<DataStoreValue<Int>>()
        val undoMaxMemory = mockk<DataStoreValue<Long>>()
        every { undoStackSize.flow } returns flowOf(100)
        every { undoMaxMemory.flow } returns flowOf(10 * 1_048_576L)
        every { settings.undoStackSize } returns undoStackSize
        every { settings.undoMaxMemory } returns undoMaxMemory
        val syntaxHighlighting = mockk<DataStoreValue<Boolean>>()
        every { syntaxHighlighting.flow } returns flowOf(true)
        every { settings.syntaxHighlighting } returns syntaxHighlighting
        return settings
    }

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

        override suspend fun commit(writer: suspend (FileCommitContext) -> Unit) =
            delegate.commit(writer)
    }

    private lateinit var breakableSource: BreakableSource

    private suspend fun createEngine(content: String): EditorEngine {
        val inMemoryDataSourceFactory = object : InMemoryDataSource.Factory {
            override fun create(workspaceId: Workspace.Id, initialContent: String) =
                InMemoryDataSource(workspaceId, initialContent)
        }
        val documentBufferFactory = object : DocumentBuffer.Factory {
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
            ) = DocumentBuffer(
                workspaceId,
                BreakableSource(dataSource).also { breakableSource = it },
                maxUndoStackSize,
                maxUndoMemoryBytes,
                blockSize,
                true,
                staleSampleRandom,
            )
        }
        val engine = EditorEngine(
            workspaceId = workspaceId,
            filePath = null,
            initialContent = content,
            gatewaySwitch = mockk(),
            editorSettings = createMockSettings(),
            dispatcherProvider = TestDispatcherProvider(),
            fileDataSourceFactory = mockk(),
            inMemoryDataSourceFactory = inMemoryDataSourceFactory,
            documentBufferFactory = documentBufferFactory,
        )
        engine.initialize().getOrThrow()
        return engine
    }

    @Test
    fun `selection at the cap copies successfully`() = runTest {
        val engine = createEngine("Hello World")
        engine.setSelection(
            start = TextPosition(offset = 0, line = 0, column = 0),
            end = TextPosition(offset = 5, line = 0, column = 5),
        )

        engine.copySelection(maxChars = 5).getOrThrow() shouldBe "Hello"
    }

    @Test
    fun `selection over the cap fails with ClipboardCapacityException`() = runTest {
        val engine = createEngine("Hello World")
        engine.setSelection(
            start = TextPosition(offset = 0, line = 0, column = 0),
            end = TextPosition(offset = 11, line = 0, column = 11),
        )

        val result = engine.copySelection(maxChars = 10)

        result.isFailure shouldBe true
        result.exceptionOrNull().shouldBeInstanceOf<ClipboardCapacityException>()
        // The refusal must not surface as an engine error banner - it has its own dialog path
        engine.error.value shouldBe null
    }

    @Test
    fun `no cap copies the whole selection`() = runTest {
        val engine = createEngine("Hello World")
        engine.setSelection(
            start = TextPosition(offset = 0, line = 0, column = 0),
            end = TextPosition(offset = 11, line = 0, column = 11),
        )

        engine.copySelection().getOrThrow() shouldBe "Hello World"
    }

    @Test
    fun `reversed selection is normalized for both cap and copy`() = runTest {
        val engine = createEngine("Hello World")
        // setSelection stores the given order; a reversed range must still measure and copy
        engine.setSelection(
            start = TextPosition(offset = 11, line = 0, column = 11),
            end = TextPosition(offset = 6, line = 0, column = 6),
        )

        engine.copySelection(maxChars = 4).exceptionOrNull()
            .shouldBeInstanceOf<ClipboardCapacityException>()
        engine.copySelection(maxChars = 5).getOrThrow() shouldBe "World"
    }

    // ==================== Verified cut ====================

    private suspend fun EditorEngine.documentText(): String {
        val state = state.value as EditorState.Loaded
        return state.resources.textBuffer.getText(0, state.resources.textBuffer.totalLength.value).getOrThrow()
    }

    @Test
    fun `an undisturbed cut deletes exactly the copied range`() = runTest {
        val engine = createEngine("Hello World")
        engine.setSelection(
            start = TextPosition(offset = 6, line = 0, column = 6),
            end = TextPosition(offset = 11, line = 0, column = 11),
        )

        val snapshot = engine.prepareCut().getOrThrow()
        snapshot.text shouldBe "World"

        engine.applyCut(snapshot).getOrThrow() shouldBe "World"
        engine.documentText() shouldBe "Hello "
        engine.cursorPosition.value.offset shouldBe 6
        engine.selectionRange.value shouldBe null
    }

    @Test
    fun `a cut deletes what it copied, not what is selected when it runs`() = runTest {
        // The deletion runs behind the ordered edit queue; by then the selection may sit elsewhere
        val engine = createEngine("Hello World")
        engine.setSelection(
            start = TextPosition(offset = 0, line = 0, column = 0),
            end = TextPosition(offset = 5, line = 0, column = 5),
        )
        val snapshot = engine.prepareCut().getOrThrow()

        engine.setSelection(
            start = TextPosition(offset = 6, line = 0, column = 6),
            end = TextPosition(offset = 11, line = 0, column = 11),
        )
        engine.applyCut(snapshot).getOrThrow() shouldBe "Hello"

        engine.documentText() shouldBe " World"
    }

    @Test
    fun `a cut whose document moved on deletes nothing and raises no error`() = runTest {
        // e.g. the cut's delete waited behind a slow paste that changed the document underneath it
        val engine = createEngine("Hello World")
        engine.setSelection(
            start = TextPosition(offset = 6, line = 0, column = 6),
            end = TextPosition(offset = 11, line = 0, column = 11),
        )
        val snapshot = engine.prepareCut().getOrThrow()

        engine.setCursorPosition(TextPosition(offset = 0, line = 0, column = 0))
        engine.performInsert("ABC")

        val result = engine.applyCut(snapshot)

        result.exceptionOrNull().shouldBeInstanceOf<StaleMatchException>()
        engine.documentText() shouldBe "ABCHello World"
        // A conflicted cut is a normal outcome, not something to raise a banner for
        engine.error.value shouldBe null
    }

    @Test
    fun `a cut failing for a non-stale reason raises the error banner`() = runTest {
        // The clipboard write already happened - a delete that fails for anything other than a
        // conflict must be visible, otherwise the cut half-executes silently
        val engine = createEngine("Hello World")
        engine.setSelection(
            start = TextPosition(offset = 6, line = 0, column = 6),
            end = TextPosition(offset = 11, line = 0, column = 11),
        )
        val snapshot = engine.prepareCut().getOrThrow()

        // The verify pass reads from the warm decode cache; the mutation's charToByte reads the
        // backing bytes directly and trips the failure
        engine.documentText()
        breakableSource.failWith = { FileNotFoundException("open failed: ENOENT (No such file or directory)") }

        val result = engine.applyCut(snapshot)

        result.isFailure shouldBe true
        result.exceptionOrNull().shouldNotBeInstanceOf<StaleMatchException>()
        engine.error.value shouldBe result.exceptionOrNull()
    }

    @Test
    fun `reversed selection deletes correctly (cut parity with copy)`() = runTest {
        // Copy and delete must agree on normalization - otherwise a reversed-selection cut
        // copies the text but silently fails the delete half
        val engine = createEngine("Hello World")
        engine.setSelection(
            start = TextPosition(offset = 11, line = 0, column = 11),
            end = TextPosition(offset = 6, line = 0, column = 6),
        )

        engine.performDeleteSelection().shouldBeInstanceOf<EditorEngine.EditOutcome.Applied>().removedText shouldBe "World"
        val state = engine.state.value as EditorState.Loaded
        state.resources.textBuffer.getText(0, state.resources.textBuffer.totalLength.value)
            .getOrThrow() shouldBe "Hello "
    }

    // ==================== Cut across a document switch ====================

    @Test
    fun `a cut prepared on another document deletes nothing here`() = runTest {
        // The tab switched files while the cut's deletion waited in the queue. Structural versions
        // restart per buffer, so without the epoch the range could match the new document by chance.
        val source = createEngine("Hello World")
        source.setSelection(
            start = TextPosition(offset = 6, line = 0, column = 6),
            end = TextPosition(offset = 11, line = 0, column = 11),
        )
        val snapshot = source.prepareCut().getOrThrow()

        val switched = createEngine("Hello World")
        val result = switched.applyCut(snapshot)

        result.exceptionOrNull().shouldBeInstanceOf<StaleMatchException>()
        switched.documentText() shouldBe "Hello World"
        switched.error.value shouldBe null
    }

    @Test
    fun `a foreign cut is refused before the document guards even apply`() = runTest {
        // Epoch first: whatever the current engine is doing (empty, loading, read-only), a snapshot
        // from another document must be rejected as a conflict, not as "no file open"
        val source = createEngine("Hello World")
        source.setSelection(
            start = TextPosition(offset = 0, line = 0, column = 0),
            end = TextPosition(offset = 5, line = 0, column = 5),
        )
        val snapshot = source.prepareCut().getOrThrow()

        val empty = createEngine("Hello World")
        empty.release()

        empty.applyCut(snapshot).exceptionOrNull().shouldBeInstanceOf<StaleMatchException>()
    }
}
