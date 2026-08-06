package eu.darken.butler.editor.ui.editor.text

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import eu.darken.butler.editor.core.engine.EditorEngine
import eu.darken.butler.editor.core.engine.TextPosition
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import testhelpers.BaseTest
import kotlin.uuid.Uuid

/**
 * The input session's bookkeeping, without Compose: deltas must be dispatched in typing order and
 * mapped through the field's OWN evolving text (a keystroke burst is computed against state the
 * engine has not seen yet), outcomes must be processed in emission order, and a conflict must
 * invalidate its whole lineage so late acknowledgements can never revive it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EditorInputSessionTest : BaseTest() {

    private val epoch = Uuid.random()

    private fun token(version: Long) = EditorEngine.DocumentToken(epoch, version)

    private val emptySnapshot = EditorEngine.WindowSnapshot(
        content = EditorEngine.VisibleContent(text = "rebuilt", token = token(99)),
        cursor = TextPosition.ZERO,
        selection = null,
    )

    /** Records every dispatched delta and lets the test decide each outcome. */
    private class Recorder {
        val dispatched = mutableListOf<SessionDelta>()
        val outcomes = mutableListOf<CompletableDeferred<EditorEngine.MutationResult>>()

        val enqueue: (SessionDelta) -> Deferred<EditorEngine.MutationResult> = { delta ->
            dispatched += delta
            CompletableDeferred<EditorEngine.MutationResult>().also { outcomes += it }
        }
    }

    /** Drives the session the way the field does: keep the new value, dispatch the diff. */
    private fun EditorInputSession.type(from: String, to: String, caret: Int = to.length) {
        val edit = computeTextEdit(from, to) ?: return
        onFieldEdit(from, edit, TextFieldValue(text = to, selection = TextRange(caret)))
    }

    private fun EditorInputSession.rebaseOn(
        text: String,
        version: Long = 1L,
        rangeStart: Long = 0L,
        startColumns: Map<Long, Long> = emptyMap(),
    ) = rebase(token(version), rangeStart, text.split('\n'), startColumns)

    @Test
    fun `deltas are dispatched in typing order`() = runTest {
        val recorder = Recorder()
        val session = EditorInputSession(TestScope(testScheduler), recorder.enqueue)
        session.rebaseOn("")

        session.type("", "a")
        session.type("a", "ab")
        session.type("ab", "abc")

        recorder.dispatched.map { it.newText } shouldBe listOf("a", "b", "c")
        session.hasUnackedWork shouldBe true
    }

    @Test
    fun `only the first delta of a generation carries the snapshot token`() = runTest {
        val recorder = Recorder()
        val session = EditorInputSession(TestScope(testScheduler), recorder.enqueue)
        session.rebaseOn("", version = 40L)

        session.type("", "a")
        session.type("a", "ab")

        recorder.dispatched[0].snapshotToken shouldBe token(40)
        recorder.dispatched[1].snapshotToken.shouldBeNull()
        recorder.dispatched[0].generation shouldBe recorder.dispatched[1].generation
    }

    @Test
    fun `the local mapping follows an unacknowledged line break`() = runTest {
        val recorder = Recorder()
        val session = EditorInputSession(TestScope(testScheduler), recorder.enqueue)
        session.rebaseOn("")

        session.type("", "a")
        session.type("a", "a\n")
        session.type("a\n", "a\nb")

        // Without the local mapping the third delta would be (line 0, column 1) and the document
        // would end up as "ab\n"
        val third = recorder.dispatched.last()
        third.start.line shouldBe 1L
        third.start.column shouldBe 0
        third.caret.line shouldBe 1L
        third.caret.column shouldBe 1
    }

    @Test
    fun `window anchors shift with the lines an unacknowledged edit moves`() = runTest {
        val recorder = Recorder()
        val session = EditorInputSession(TestScope(testScheduler), recorder.enqueue)
        // Line 1 is horizontally windowed at column 10; a split on line 0 pushes it to line 2
        session.rebaseOn("ab\ncd", startColumns = mapOf(1L to 10L))

        session.type("ab\ncd", "a\nb\ncd", caret = 2)
        session.type("a\nb\ncd", "a\nb\nXcd", caret = 5)

        val second = recorder.dispatched.last()
        second.start.line shouldBe 2L
        // The anchor moved with the line, so the column is absolute again
        second.start.column shouldBe 10
    }

    @Test
    fun `a delta replaying against a reference buffer reproduces the field text`() = runTest {
        val recorder = Recorder()
        val session = EditorInputSession(TestScope(testScheduler), recorder.enqueue)
        session.rebaseOn("hello world")

        var field = "hello world"
        for (next in listOf("hello worldX", "hello worldXY", "hello\nworldXY", "hell\nworldXY")) {
            session.type(field, next)
            field = next
        }

        // Applying the dispatched deltas to the same starting text must land on the field's text
        var reference = "hello world"
        for (delta in recorder.dispatched) {
            val lines = reference.split('\n')
            fun flat(position: TextPosition): Int {
                var offset = 0
                for (line in 0 until position.line.toInt()) offset += lines[line].length + 1
                return offset + position.column
            }
            reference = reference.substring(0, flat(delta.start)) + delta.newText +
                reference.substring(flat(delta.end))
        }
        reference shouldBe field
    }

    @Test
    fun `an acknowledgement clears the unacked state and bumps the revision`() = runTest {
        val recorder = Recorder()
        val session = EditorInputSession(TestScope(testScheduler), recorder.enqueue)
        session.rebaseOn("")

        val beforeEnqueue = session.state.value
        session.type("", "a")
        val afterEnqueue = session.state.value
        afterEnqueue shouldBe beforeEnqueue + 1
        session.hasUnackedWork shouldBe true

        recorder.outcomes.single().complete(EditorEngine.MutationResult.Applied(token(2)))
        runCurrent()

        session.hasUnackedWork shouldBe false
        session.state.value shouldBe afterEnqueue + 1
        session.consumePendingRebuild().shouldBeNull()
    }

    @Test
    fun `outcomes are processed in the order their deltas were dispatched`() = runTest {
        val recorder = Recorder()
        val session = EditorInputSession(TestScope(testScheduler), recorder.enqueue)
        session.rebaseOn("")

        session.type("", "a")
        session.type("a", "ab")

        // The SECOND delta conflicts, the first is acknowledged - completed out of order
        recorder.outcomes[1].complete(EditorEngine.MutationResult.Conflict(emptySnapshot))
        recorder.outcomes[0].complete(EditorEngine.MutationResult.Applied(token(2)))
        runCurrent()

        // Processing in dispatch order means the conflict lands last and stays in effect
        session.hasUnackedWork shouldBe false
        session.consumePendingRebuild().shouldNotBeNull()
    }

    @Test
    fun `a conflict invalidates the lineage and offers its snapshot for rebuilding`() = runTest {
        val recorder = Recorder()
        val session = EditorInputSession(TestScope(testScheduler), recorder.enqueue)
        session.rebaseOn("", version = 40L)
        session.type("", "a")
        val conflictedGeneration = recorder.dispatched.single().generation

        recorder.outcomes.single().complete(EditorEngine.MutationResult.Conflict(emptySnapshot))
        runCurrent()

        session.hasUnackedWork shouldBe false
        session.token.shouldBeNull()
        val rebuild = session.consumePendingRebuild().shouldNotBeNull()
        rebuild.content.text shouldBe "rebuilt"
        // Consumed once
        session.consumePendingRebuild().shouldBeNull()

        // The field rebuilt from the payload; everything typed afterwards is a NEW lineage
        session.rebase(rebuild.content.token!!, 0L, listOf("rebuilt"), emptyMap())
        session.type("rebuilt", "rebuiltX")
        recorder.dispatched.last().generation shouldBeGreaterThan conflictedGeneration
        recorder.dispatched.last().snapshotToken shouldBe token(99)
    }

    @Test
    fun `a late acknowledgement from an abandoned lineage is discarded`() = runTest {
        val recorder = Recorder()
        val session = EditorInputSession(TestScope(testScheduler), recorder.enqueue)
        session.rebaseOn("")
        session.type("", "a")

        session.cancelPending()
        val afterCancel = session.state.value
        session.hasUnackedWork shouldBe false

        recorder.outcomes.single().complete(EditorEngine.MutationResult.Applied(token(2)))
        runCurrent()

        // Neither the unacked counter nor the revision may move for a dead generation
        session.state.value shouldBe afterCancel
        session.consumePendingRebuild().shouldBeNull()
    }

    @Test
    fun `an exceptional outcome invalidates instead of wedging the session`() = runTest {
        val recorder = Recorder()
        val session = EditorInputSession(TestScope(testScheduler), recorder.enqueue)
        session.rebaseOn("")
        session.type("", "a")

        recorder.outcomes.single().completeExceptionally(IllegalStateException("boom"))
        runCurrent()

        // The window must not stay pinned on a delta that will never be acknowledged
        session.hasUnackedWork shouldBe false
        session.token.shouldBeNull()
    }

    @Test
    fun `a failed outcome invalidates the lineage as well`() = runTest {
        val recorder = Recorder()
        val session = EditorInputSession(TestScope(testScheduler), recorder.enqueue)
        session.rebaseOn("")
        session.type("", "a")

        recorder.outcomes.single().complete(
            EditorEngine.MutationResult.Failed(IllegalStateException("read-only")),
        )
        runCurrent()

        session.hasUnackedWork shouldBe false
        session.token.shouldBeNull()
        // No snapshot came with it, so there is nothing to rebuild from - the next published
        // window rebases the session instead
        session.consumePendingRebuild().shouldBeNull()
    }

    @Test
    fun `cancelling bumps the revision so the field re-evaluates`() = runTest {
        val recorder = Recorder()
        val session = EditorInputSession(TestScope(testScheduler), recorder.enqueue)
        session.rebaseOn("")
        session.type("", "a")
        val before = session.state.value

        session.cancelPending()

        session.state.value shouldBe before + 1
    }

    @Test
    fun `rebase adopts the window and matchesWindow recognises it`() = runTest {
        val recorder = Recorder()
        val session = EditorInputSession(TestScope(testScheduler), recorder.enqueue)

        session.matchesWindow(token(1), 0L, listOf("abc"), emptyMap()) shouldBe false
        session.rebaseOn("abc", version = 1L)

        session.token shouldBe token(1)
        session.matchesWindow(token(1), 0L, listOf("abc"), emptyMap()) shouldBe true
        // Any part of the window moving means the field has to rebase again
        session.matchesWindow(token(2), 0L, listOf("abc"), emptyMap()) shouldBe false
        session.matchesWindow(token(1), 5L, listOf("abc"), emptyMap()) shouldBe false
        session.matchesWindow(token(1), 0L, listOf("abd"), emptyMap()) shouldBe false
        session.matchesWindow(token(1), 0L, listOf("abc"), mapOf(0L to 2L)) shouldBe false
    }

    @Test
    fun `localOffsetFor maps an engine position into the field's current text`() = runTest {
        val recorder = Recorder()
        val session = EditorInputSession(TestScope(testScheduler), recorder.enqueue)
        session.rebaseOn("abc\ndef", rangeStart = 10L)

        session.localOffsetFor(TextPosition(0L, 11L, 2)) shouldBe 6
        // Outside the window there is nothing to map to
        session.localOffsetFor(TextPosition(0L, 99L, 0)).shouldBeNull()

        // It follows unacknowledged edits, which is what a mid-burst tap relies on
        session.type("abc\ndef", "abcX\ndef")
        session.localOffsetFor(TextPosition(0L, 11L, 2)) shouldBe 7
    }

    @Test
    fun `a field edit without an adopted window is dropped`() = runTest {
        val recorder = Recorder()
        val session = EditorInputSession(TestScope(testScheduler), recorder.enqueue)

        session.type("", "a")

        recorder.dispatched.shouldBeEmptyList()
        session.hasUnackedWork shouldBe false
    }

    private fun <T> List<T>.shouldBeEmptyList() = size shouldBe 0
}
