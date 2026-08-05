package eu.darken.butler.editor.ui.editor

import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.local.LocalPathLookup
import eu.darken.butler.common.files.metadata.FileType
import eu.darken.butler.editor.core.EditorWorkspace
import eu.darken.butler.editor.core.engine.TextPosition
import eu.darken.butler.editor.ui.editor.elements.EditorActionBarItem
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.WorkspaceProvider
import eu.darken.butler.workspace.core.WorkspaceRemote
import eu.darken.butler.workspace.core.clipboard.ClipboardClip
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import testhelpers.BaseTest
import testhelpers.coroutine.TestDispatcherProvider
import java.io.File
import java.io.IOException
import kotlin.time.Duration.Companion.seconds

/**
 * The serialized edit pipeline: every text mutation is enqueued synchronously and drained by a
 * single consumer, so an edit that suspends cannot let the next one overtake it (Enter followed by
 * a character used to be able to resolve against the pre-Enter document). Rejections resync the
 * hidden field, but only when no newer input is still queued behind them.
 *
 * Clipboard ops are the exception to "queue it and it will be applied": they mutate the document
 * from inside the queue, so a field edit raised while one is pending has stale positions and is
 * dropped rather than queued behind it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EditorWorkspaceViewModelEditQueueTest : BaseTest() {

    private val workspaceId = Workspace.Id()
    private val pos = TextPosition(0, 0, 0)

    @BeforeEach
    fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterEach
    fun teardown() {
        Dispatchers.resetMain()
    }

    /** Completed by a sentinel insert: the consumer is sequential, so everything before it is done. */
    private val drained = CompletableDeferred<Unit>()

    private fun makeWorkspace(): EditorWorkspace = mockk<EditorWorkspace>().apply {
        every { info } returns MutableStateFlow(
            Workspace.Info(id = workspaceId, type = Workspace.Type.EDITOR, title = "test".toCaString()),
        )
        every { state } returns MutableStateFlow<EditorWorkspace.State>(
            EditorWorkspace.State.Ready(EditorWorkspace.EditorState(maxUndoableEditChars = 1_000_000L)),
        )
        coEvery { selectionExceedsUndoThreshold() } returns false
        coEvery { insertText(any()) } answers { drained.complete(Unit); Unit }
    }

    private fun makeViewModel(
        workspace: EditorWorkspace,
        // Defaults to Unconfined; pass a scheduler-backed dispatcher when the test needs virtual time
        dispatcher: CoroutineDispatcher? = null,
    ): EditorWorkspaceViewModel {
        val remote = mockk<WorkspaceRemote> {
            every { events } returns emptyFlow()
            every { state } returns emptyFlow()
        }
        val provider = mockk<WorkspaceProvider> {
            every { retrieve(workspaceId) } returns flowOf(workspace)
        }
        return EditorWorkspaceViewModel(
            id = workspaceId,
            dispatchers = TestDispatcherProvider(dispatcher),
            workspaceProvider = provider,
            workspaceRemote = remote,
            clipboardHelper = mockk(relaxed = true),
            clipboardRepo = mockk(relaxed = true),
            filenameValidator = mockk(relaxed = true),
        )
    }

    @Test
    fun `a suspending edit cannot be overtaken by the next one`() = runTest {
        val applied = mutableListOf<String>()
        val newlineStarted = CompletableDeferred<Unit>()
        val releaseNewline = CompletableDeferred<Unit>()
        val workspace = makeWorkspace().apply {
            coEvery { replaceText(any(), any(), any(), any()) } coAnswers {
                val text = arg<String>(2)
                if (text == "\n") {
                    newlineStarted.complete(Unit)
                    releaseNewline.await()
                }
                applied += text
                true
            }
        }
        val vm = makeViewModel(workspace)

        vm.replaceText(pos, pos, "\n", pos)
        vm.replaceText(pos, pos, "X", pos)

        newlineStarted.await()
        releaseNewline.complete(Unit)
        vm.insertText("sentinel")
        drained.await()

        applied shouldBe listOf("\n", "X")
    }

    @Test
    fun `a rejected field edit resyncs the field`() = runTest {
        val workspace = makeWorkspace().apply {
            coEvery { replaceText(any(), any(), any(), any()) } returns false
        }
        val vm = makeViewModel(workspace)

        vm.replaceText(pos, pos, "X", pos)

        vm.state.first { it.editResyncSignal == 1 }
    }

    @Test
    fun `only the newest rejection resyncs the field`() = runTest {
        // Both edits are dropped, but the first is superseded by input already queued behind it -
        // resyncing on it would rebuild the field over the newer keystroke.
        val startedX = CompletableDeferred<Unit>()
        val releaseX = CompletableDeferred<Unit>()
        val startedY = CompletableDeferred<Unit>()
        val releaseY = CompletableDeferred<Unit>()
        val workspace = makeWorkspace().apply {
            coEvery { replaceText(any(), any(), any(), any()) } coAnswers {
                when (arg<String>(2)) {
                    "X" -> { startedX.complete(Unit); releaseX.await() }
                    else -> { startedY.complete(Unit); releaseY.await() }
                }
                false
            }
        }
        val vm = makeViewModel(workspace)

        vm.replaceText(pos, pos, "X", pos)
        vm.replaceText(pos, pos, "Y", pos)

        startedX.await()
        releaseX.complete(Unit)
        // Y having started means X's command finished, resync decision included
        startedY.await()
        vm.state.first().editResyncSignal shouldBe 0

        releaseY.complete(Unit)
        vm.state.first { it.editResyncSignal == 1 }
    }

    @Test
    fun `a command that is not a replace does not suppress the resync`() = runTest {
        // Only a Replace can resolve against the field's stale positions, so only a newer Replace may
        // hold back the revert - an Undo (or paste, delete) queued behind the rejection must not.
        val startedX = CompletableDeferred<Unit>()
        val releaseX = CompletableDeferred<Unit>()
        val workspace = makeWorkspace().apply {
            coEvery { replaceText(any(), any(), any(), any()) } coAnswers {
                startedX.complete(Unit)
                releaseX.await()
                false
            }
            coEvery { undo() } returns Result.success(null)
        }
        val vm = makeViewModel(workspace)

        vm.replaceText(pos, pos, "X", pos)
        startedX.await()
        vm.undo()
        releaseX.complete(Unit)

        vm.state.first { it.editResyncSignal == 1 }
    }

    // ==================== Clipboard operations ====================

    @Test
    fun `a keystroke during a pending cut is dropped, and input resumes after it`() = runTest {
        val applied = mutableListOf<String>()
        val deleteStarted = CompletableDeferred<Unit>()
        val releaseDelete = CompletableDeferred<Unit>()
        val workspace = makeWorkspace().apply {
            coEvery { copySelection(any()) } returns Result.success("cut me")
            coEvery { deleteSelection() } coAnswers {
                deleteStarted.complete(Unit)
                releaseDelete.await()
                applied += "cut"
                Result.success("cut me")
            }
            coEvery { replaceText(any(), any(), any(), any()) } coAnswers { applied += arg<String>(2); true }
        }
        val vm = makeViewModel(workspace)

        vm.executeAction(EditorActionBarItem.Cut)
        deleteStarted.await()
        // Positions taken from the not-yet-cut document; queueing it behind the cut would misapply it
        vm.replaceText(pos, pos, "X", pos)
        releaseDelete.complete(Unit)

        vm.state.first { !it.isClipboardBusy }
        vm.replaceText(pos, pos, "Y", pos)
        vm.insertText("sentinel")
        drained.await()

        applied shouldBe listOf("cut", "Y")
    }

    @Test
    fun `a keystroke during a pending paste is dropped and resyncs the field`() = runTest {
        // The keystroke's positions were captured from the pre-paste document and often stay
        // representable afterwards, so the engine's column check would let them through.
        val applied = mutableListOf<String>()
        val readStarted = CompletableDeferred<Unit>()
        val releaseRead = CompletableDeferred<Unit>()
        val workspace = makeWorkspace().apply {
            coEvery { readFileContent(any()) } coAnswers {
                readStarted.complete(Unit)
                releaseRead.await()
                Result.success("pasted")
            }
            coEvery { insertText(any()) } answers {
                val text = arg<String>(0)
                applied += text
                if (text == "sentinel") drained.complete(Unit)
            }
            coEvery { replaceText(any(), any(), any(), any()) } coAnswers { applied += arg<String>(2); true }
        }
        val vm = makeViewModel(workspace)

        vm.onPageAction(EditorPageAction.Clipboard.Paste(pathsClip("notes.txt")))
        readStarted.await()
        vm.state.first().isClipboardBusy shouldBe true

        vm.replaceText(pos, pos, "X", pos)
        vm.state.first { it.editResyncSignal == 1 }

        releaseRead.complete(Unit)
        vm.insertText("sentinel")
        drained.await()

        applied shouldBe listOf("pasted", "sentinel")
    }

    @Test
    fun `a throwing clipboard op re-enables input`() = runTest {
        val applied = mutableListOf<String>()
        val workspace = makeWorkspace().apply {
            coEvery { readFileContent(any()) } returns Result.failure(IOException("nope"))
            coEvery { insertText(any()) } answers {
                val text = arg<String>(0)
                applied += text
                if (text == "sentinel") drained.complete(Unit)
            }
            coEvery { replaceText(any(), any(), any(), any()) } coAnswers { applied += arg<String>(2); true }
        }
        val vm = makeViewModel(workspace)

        vm.onPageAction(EditorPageAction.Clipboard.Paste(pathsClip("notes.txt")))
        vm.errorEvents.first().shouldBeInstanceOf<IOException>()

        vm.state.first().isClipboardBusy shouldBe false
        vm.replaceText(pos, pos, "X", pos)
        vm.insertText("sentinel")
        drained.await()

        applied shouldBe listOf("X", "sentinel")
    }

    @Test
    fun `a file read that never returns fails without starving the queue`() = runTest {
        val applied = mutableListOf<String>()
        val workspace = makeWorkspace().apply {
            coEvery { readFileContent(any()) } coAnswers { awaitCancellation() }
            coEvery { insertText(any()) } answers {
                val text = arg<String>(0)
                applied += text
                if (text == "sentinel") drained.complete(Unit)
            }
        }
        // Scheduler-backed so the read timeout elapses in virtual instead of wall-clock time
        val vm = makeViewModel(workspace, StandardTestDispatcher(testScheduler))

        vm.onPageAction(EditorPageAction.Clipboard.Paste(pathsClip("notes.txt")))
        advanceTimeBy(31.seconds)
        runCurrent()

        vm.errorEvents.first().shouldBeInstanceOf<IOException>()

        // The consumer survived the failed command
        vm.insertText("sentinel")
        runCurrent()
        applied shouldBe listOf("sentinel")
    }

    private fun pathsClip(name: String) = ClipboardClip.Paths(
        origin = workspaceId,
        mode = ClipboardClip.Paths.Mode.COPY,
        paths = listOf(
            LocalPathLookup(
                lookedUp = LocalPath.build(File("/tmp/queue-test", name)),
                fileType = FileType.FILE,
                size = null,
                modifiedAt = null,
            ),
        ),
    )
}
