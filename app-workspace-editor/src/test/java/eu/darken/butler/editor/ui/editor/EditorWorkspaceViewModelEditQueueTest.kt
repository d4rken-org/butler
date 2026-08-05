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
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import testhelpers.BaseTest
import testhelpers.coroutine.TestDispatcherProvider
import java.io.File

/**
 * The serialized edit pipeline: every text mutation is enqueued synchronously and drained by a
 * single consumer, so an edit that suspends cannot let the next one overtake it (Enter followed by
 * a character used to be able to resolve against the pre-Enter document). Rejections resync the
 * hidden field, but only when no newer input is still queued behind them.
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

    private fun makeViewModel(workspace: EditorWorkspace): EditorWorkspaceViewModel {
        val remote = mockk<WorkspaceRemote> {
            every { events } returns emptyFlow()
            every { state } returns emptyFlow()
        }
        val provider = mockk<WorkspaceProvider> {
            every { retrieve(workspaceId) } returns flowOf(workspace)
        }
        return EditorWorkspaceViewModel(
            id = workspaceId,
            dispatchers = TestDispatcherProvider(),
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
    fun `a cut's deletion is ordered against a later keystroke`() = runTest {
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
        vm.replaceText(pos, pos, "X", pos)
        releaseDelete.complete(Unit)
        vm.insertText("sentinel")
        drained.await()

        applied shouldBe listOf("cut", "X")
    }

    @Test
    fun `a paste whose retrieval suspends still applies before a later keystroke`() = runTest {
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

        // File retrieval runs inside the queued op, so the keystroke can't jump ahead of the insert
        vm.onPageAction(EditorPageAction.Clipboard.Paste(pathsClip("notes.txt")))
        readStarted.await()
        vm.replaceText(pos, pos, "X", pos)
        releaseRead.complete(Unit)
        vm.insertText("sentinel")
        drained.await()

        applied shouldBe listOf("pasted", "X", "sentinel")
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
