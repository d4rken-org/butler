package eu.darken.butler.editor.core.engine

import eu.darken.butler.editor.core.sources.InMemoryDataSource
import eu.darken.butler.workspace.core.Workspace
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import testhelpers.BaseTest
import kotlin.time.TestTimeSource
import kotlin.uuid.Uuid

/**
 * Typing-run coalescing: keystroke-sized edits flagged with the coalesce hint merge into one
 * undo entry, so undo steps back over a typing run instead of one character at a time (the
 * default 100-entry stack previously lost ALL history after ~100 keystrokes).
 */
class DocumentBufferCoalescingTest : BaseTest() {

    private val workspaceId = Workspace.Id(Uuid.random())
    private val timeSource = TestTimeSource()

    private suspend fun createBuffer(content: String = ""): DocumentBuffer {
        val buffer = DocumentBuffer(
            workspaceId = workspaceId,
            dataSource = InMemoryDataSource(workspaceId, content),
            maxUndoStackSize = 100,
            maxUndoMemoryBytes = 10_485_760,
            blockSize = 1024,
            assertions = true,
            timeSource = timeSource,
        )
        buffer.initialize().getOrThrow()
        return buffer
    }

    private suspend fun DocumentBuffer.typeAt(offset: Long, text: String) {
        insertText(TextPosition(offset, 0, offset.toInt()), text, coalesce = true).getOrThrow()
    }

    private suspend fun DocumentBuffer.backspaceAt(offset: Long) {
        deleteText(
            TextPosition(offset - 1, 0, (offset - 1).toInt()),
            TextPosition(offset, 0, offset.toInt()),
            coalesce = true,
        ).getOrThrow()
    }

    @Test
    fun `a typing run within the window collapses to one undo entry`() = runTest {
        val buffer = createBuffer()
        "hello".forEachIndexed { i, c -> buffer.typeAt(i.toLong(), c.toString()) }

        buffer.getFullText().getOrThrow() shouldBe "hello"

        buffer.undo().getOrThrow()
        buffer.getFullText().getOrThrow() shouldBe ""
        buffer.canUndo().shouldBeFalse()

        buffer.redo().getOrThrow()
        buffer.getFullText().getOrThrow() shouldBe "hello"
    }

    @Test
    fun `a pause longer than the window starts a new entry`() = runTest {
        val buffer = createBuffer()
        buffer.typeAt(0, "hel")
        timeSource += DocumentBuffer.COALESCE_WINDOW * 2
        buffer.typeAt(3, "lo")

        buffer.undo().getOrThrow()
        buffer.getFullText().getOrThrow() shouldBe "hel"
        buffer.undo().getOrThrow()
        buffer.getFullText().getOrThrow() shouldBe ""
    }

    @Test
    fun `a backspace run collapses to one undo entry`() = runTest {
        val buffer = createBuffer("hello")
        buffer.backspaceAt(5)
        buffer.backspaceAt(4)
        buffer.backspaceAt(3)
        buffer.getFullText().getOrThrow() shouldBe "he"

        buffer.undo().getOrThrow()
        buffer.getFullText().getOrThrow() shouldBe "hello"
        buffer.canUndo().shouldBeFalse()
    }

    @Test
    fun `a newline breaks the typing run`() = runTest {
        val buffer = createBuffer()
        buffer.typeAt(0, "line")
        buffer.typeAt(4, "\n")
        buffer.typeAt(5, "next")

        buffer.undo().getOrThrow()
        buffer.getFullText().getOrThrow() shouldBe "line\n"
        buffer.undo().getOrThrow()
        buffer.getFullText().getOrThrow() shouldBe "line"
    }

    @Test
    fun `a carriage return breaks the typing run`() = runTest {
        val buffer = createBuffer()
        buffer.typeAt(0, "line")
        buffer.typeAt(4, "\r")
        buffer.typeAt(5, "next")

        buffer.undo().getOrThrow()
        buffer.getFullText().getOrThrow() shouldBe "line\r"
        buffer.undo().getOrThrow()
        buffer.getFullText().getOrThrow() shouldBe "line"
    }

    @Test
    fun `a backspaced carriage return breaks the backspace run`() = runTest {
        val buffer = createBuffer("a\rb")
        buffer.backspaceAt(3)
        buffer.backspaceAt(2)
        buffer.backspaceAt(1)
        buffer.getFullText().getOrThrow() shouldBe ""

        buffer.undo().getOrThrow()
        buffer.getFullText().getOrThrow() shouldBe "a"
        buffer.undo().getOrThrow()
        buffer.getFullText().getOrThrow() shouldBe "a\r"
    }

    @Test
    fun `a cursor jump breaks the typing run`() = runTest {
        val buffer = createBuffer()
        buffer.typeAt(0, "ab")
        buffer.breakUndoRun()
        buffer.typeAt(2, "cd")

        buffer.undo().getOrThrow()
        buffer.getFullText().getOrThrow() shouldBe "ab"
    }

    @Test
    fun `non-contiguous input breaks the typing run`() = runTest {
        val buffer = createBuffer("XY")
        buffer.typeAt(2, "a")
        // Typing somewhere else entirely (offset 0, not the run end at 3)
        buffer.typeAt(0, "b")

        buffer.undo().getOrThrow()
        buffer.getFullText().getOrThrow() shouldBe "XYa"
    }

    @Test
    fun `un-hinted edits never coalesce`() = runTest {
        val buffer = createBuffer()
        buffer.insertText(TextPosition(0, 0, 0), "a").getOrThrow()
        buffer.insertText(TextPosition(1, 0, 1), "b").getOrThrow()

        buffer.undo().getOrThrow()
        buffer.getFullText().getOrThrow() shouldBe "a"
    }

    @Test
    fun `the merged run is capped in length`() = runTest {
        val buffer = createBuffer()
        val chunk = "x".repeat(DocumentBuffer.COALESCE_MAX_CHARS - 1)
        buffer.typeAt(0, chunk)
        buffer.typeAt(chunk.length.toLong(), "y") // still within cap -> merges
        buffer.typeAt(chunk.length + 1L, "z") // would exceed cap -> new entry

        buffer.undo().getOrThrow()
        buffer.getFullText().getOrThrow() shouldBe chunk + "y"
    }

    @Test
    fun `saving breaks the run and keeps the checkpoint exact`() = runTest {
        val buffer = createBuffer()
        buffer.typeAt(0, "saved")
        buffer.saveFile().getOrThrow()
        buffer.isModified.value.shouldBeFalse()

        buffer.typeAt(5, " more")
        buffer.isModified.value.shouldBeTrue()

        // Undo removes only the post-save typing; the checkpoint is reachable and exact
        buffer.undo().getOrThrow()
        buffer.getFullText().getOrThrow() shouldBe "saved"
        buffer.isModified.value.shouldBeFalse()
    }

    @Test
    fun `undo breaks the run so redo is not clobbered by the next keystroke merge`() = runTest {
        val buffer = createBuffer()
        buffer.typeAt(0, "abc")
        buffer.undo().getOrThrow()
        buffer.getFullText().getOrThrow() shouldBe ""

        buffer.typeAt(0, "x")
        buffer.getFullText().getOrThrow() shouldBe "x"
        // The old run must not resurface
        buffer.undo().getOrThrow()
        buffer.getFullText().getOrThrow() shouldBe ""
        buffer.canRedo().shouldBeTrue()
    }

    @Test
    fun `memory accounting survives merge and eviction`() = runTest {
        val buffer = DocumentBuffer(
            workspaceId = workspaceId,
            dataSource = InMemoryDataSource(workspaceId, ""),
            maxUndoStackSize = 2,
            maxUndoMemoryBytes = 10_485_760,
            blockSize = 1024,
            assertions = true,
            timeSource = timeSource,
        ).apply { initialize().getOrThrow() }

        // Three separate runs against a 2-entry cap: the oldest run gets evicted
        buffer.typeAt(0, "aa")
        buffer.breakUndoRun()
        buffer.typeAt(2, "bb")
        buffer.breakUndoRun()
        buffer.typeAt(4, "cc")

        buffer.undo().getOrThrow()
        buffer.undo().getOrThrow()
        buffer.getFullText().getOrThrow() shouldBe "aa"
        buffer.canUndo().shouldBeFalse()
        // Evicting across the epoch start invalidates the unmodified checkpoint
        buffer.isModified.value.shouldBeTrue()
    }
}
