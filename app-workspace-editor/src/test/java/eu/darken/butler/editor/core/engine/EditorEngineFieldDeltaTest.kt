package eu.darken.butler.editor.core.engine

import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.uuid.Uuid

/**
 * The field-delta protocol: a delta is accepted only against the document state its token names and
 * only if the range it claims to replace still holds its text. Everything else is a conflict - an
 * expected synchronization outcome that mutates nothing, raises no error banner, and hands the
 * field an authoritative snapshot to rebuild from.
 */
class EditorEngineFieldDeltaTest : EditorEngineTestBase() {

    private val EditorEngine.windowToken: EditorEngine.DocumentToken
        get() = visibleContent.value.token!!

    @Test
    fun `a delta chained on its predecessor's token is applied`() = runTest {
        val engine = createEngine("ab")

        val first = engine.applyDelta(pos(0, 2), newText = "c", caret = pos(0, 3))
            .shouldBeInstanceOf<EditorEngine.MutationResult.Applied>()
        // The second keystroke of a burst is computed against the field's own state, so it chains
        // on the token the first one returned - not on any published window.
        engine.applyDelta(pos(0, 3), newText = "d", caret = pos(0, 4), token = first.token)
            .shouldBeInstanceOf<EditorEngine.MutationResult.Applied>()

        engine.fullContent() shouldBe "abcd"
    }

    @Test
    fun `a foreign mutation between snapshot and delta conflicts`() = runTest {
        val engine = createEngine("hello")
        val snapshotToken = engine.windowToken

        // Something else moved the document (paste, undo, replace-all, ...)
        engine.setCursorPosition(pos(0, 5))
        engine.performInsert(" world")

        val conflict = engine.applyDelta(pos(0, 5), newText = "X", caret = pos(0, 6), token = snapshotToken)
            .shouldBeInstanceOf<EditorEngine.MutationResult.Conflict>()

        engine.fullContent() shouldBe "hello world"
        // The payload describes the document as it is NOW, so the field can rebuild from it alone
        conflict.snapshot.content.text shouldBe "hello world"
        conflict.snapshot.content.token shouldBe engine.windowToken
        conflict.snapshot.content.token shouldNotBe snapshotToken
        engine.error.value shouldBe null
    }

    @Test
    fun `a diverged old text conflicts without mutating`() = runTest {
        val engine = createEngine("hello")

        engine.applyDelta(pos(0, 0), pos(0, 3), oldText = "hel!", caret = pos(0, 0))
            .shouldBeInstanceOf<EditorEngine.MutationResult.Conflict>()

        engine.fullContent() shouldBe "hello"
        engine.error.value shouldBe null
    }

    @Test
    fun `a token from another engine epoch conflicts`() = runTest {
        val engine = createEngine("hello")
        val foreign = EditorEngine.DocumentToken(Uuid.random(), engine.windowToken.structuralVersion)

        engine.applyDelta(pos(0, 0), newText = "X", caret = pos(0, 1), token = foreign)
            .shouldBeInstanceOf<EditorEngine.MutationResult.Conflict>()

        engine.fullContent() shouldBe "hello"
    }

    @Test
    fun `the caret lands where the delta says`() = runTest {
        val engine = createEngine("abc\ndef")

        engine.applyDelta(pos(1, 1), newText = "XY", caret = pos(1, 3))

        engine.fullContent() shouldBe "abc\ndXYef"
        engine.cursorPosition.value.line shouldBe 1L
        engine.cursorPosition.value.column shouldBe 3
    }

    @Test
    fun `keystroke deltas coalesce into one undo step`() = runTest {
        val engine = createEngine("")

        var token = engine.windowToken
        for ((index, char) in "abc".withIndex()) {
            token = engine.applyDelta(
                start = pos(0, index),
                newText = char.toString(),
                caret = pos(0, index + 1),
                token = token,
            ).shouldBeInstanceOf<EditorEngine.MutationResult.Applied>().token
        }

        engine.fullContent() shouldBe "abc"
        engine.performUndo().getOrThrow()
        engine.fullContent() shouldBe ""
    }

    // ==================== Line-ending forms ====================

    @ParameterizedTest
    @ValueSource(strings = ["\n", "\r\n", "\r"])
    fun `backspacing a line break works whatever form the document holds`(terminator: String) = runTest {
        // The field joins its window lines with '\n' regardless of the document's real break, so
        // old-text verification has to compare modulo break FORM.
        val engine = createEngine("Hello${terminator}World")
        engine.visibleContent.value.text shouldBe "Hello\nWorld"

        engine.applyDelta(pos(0, 5), pos(1, 0), oldText = "\n", caret = pos(0, 5))
            .shouldBeInstanceOf<EditorEngine.MutationResult.Applied>()

        engine.fullContent() shouldBe "HelloWorld"
        engine.totalLines.value shouldBe 1L
    }

    @Test
    fun `backspacing a CRLF break in a mixed document works too`() = runTest {
        val engine = createEngine("a\r\nb\nc")
        engine.visibleContent.value.text shouldBe "a\nb\nc"

        engine.applyDelta(pos(0, 1), pos(1, 0), oldText = "\n", caret = pos(0, 1))
            .shouldBeInstanceOf<EditorEngine.MutationResult.Applied>()

        engine.fullContent() shouldBe "ab\nc"
    }

    @Test
    fun `a delta spanning a break in a CRLF document applies byte-faithfully`() = runTest {
        val engine = createEngine("one\r\ntwo")

        engine.applyDelta(pos(0, 2), pos(1, 1), oldText = "e\nt", newText = "X", caret = pos(0, 3))
            .shouldBeInstanceOf<EditorEngine.MutationResult.Applied>()

        engine.fullContent() shouldBe "onXwo"
    }

    @Test
    fun `an inserted break conforms to a CRLF document`() = runTest {
        val engine = createEngine("one\r\ntwo")

        engine.applyDelta(pos(0, 3), newText = "\n", caret = pos(1, 0))
            .shouldBeInstanceOf<EditorEngine.MutationResult.Applied>()

        engine.fullContent() shouldBe "one\r\n\r\ntwo"
    }

    // ==================== Token freshness ====================

    @Test
    fun `the published token tracks the buffer version across every mutation path`() = runTest {
        val engine = createEngine("alpha beta\ngamma")

        suspend fun assertFresh(label: String) {
            val published = engine.visibleContent.value.token
            published shouldNotBe null
            val bufferVersion = engine.textBuffer!!.getStructuralVersion()
            withClue(label) { published!!.structuralVersion shouldBe bufferVersion }
        }

        assertFresh("after load")

        engine.setCursorPosition(pos(0, 0))
        engine.performInsert("X")
        assertFresh("after an insert intent")

        engine.setCursorPosition(pos(0, 2))
        engine.performDeleteForward()
        assertFresh("after a forward-delete intent")

        engine.performUndo()
        assertFresh("after undo")

        engine.performRedo()
        assertFresh("after redo")

        // The post-save rebase bumps the version too; without a republish here every later delta
        // would chain on a version the document has already left behind.
        engine.saveFile().getOrThrow()
        assertFresh("after saveFile")

        engine.setSelection(pos(0, 0), pos(0, 5))
        val cut = engine.prepareCut().getOrThrow()
        engine.applyCut(cut)
        assertFresh("after applyCut")

        engine.replaceAll("gamma", SearchOptions(), "delta").getOrThrow()
        assertFresh("after replaceAll")

        engine.convertLineEndings(LineEnding.CRLF).getOrThrow()
        assertFresh("after convertLineEndings")
    }

    @Test
    fun `a delta applied against the freshly published token succeeds after a foreign edit`() = runTest {
        // The regression this guards: a mutation path that forgets to republish leaves the field
        // chained to a version the document has moved past, and every later keystroke conflicts.
        val engine = createEngine("hello")
        engine.setCursorPosition(pos(0, 5))
        engine.performInsert("!")

        engine.applyDelta(pos(0, 6), newText = "X", caret = pos(0, 7))
            .shouldBeInstanceOf<EditorEngine.MutationResult.Applied>()

        engine.fullContent() shouldBe "hello!X"
    }

}
