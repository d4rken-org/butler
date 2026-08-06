package eu.darken.butler.editor.ui.editor

import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.local.LocalPathLookup
import eu.darken.butler.common.files.metadata.FileType
import eu.darken.butler.editor.core.EditorWorkspace
import eu.darken.butler.editor.core.engine.EditorEngine
import eu.darken.butler.editor.core.engine.TextPosition
import eu.darken.butler.editor.ui.editor.elements.EditorActionBarItem
import eu.darken.butler.editor.ui.editor.text.SessionDelta
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.WorkspaceProvider
import eu.darken.butler.workspace.core.WorkspaceRemote
import eu.darken.butler.workspace.core.clipboard.ClipboardClip
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.MockKAnswerScope
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
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
import kotlin.uuid.Uuid

/**
 * The serialized edit pipeline: every text mutation is enqueued synchronously and drained by a
 * single consumer, so an edit that suspends cannot let the next one overtake it (Enter followed by
 * a character used to be able to resolve against the pre-Enter document).
 *
 * Field deltas additionally CHAIN: the first of a generation carries the token of the window it was
 * computed against, its successors are applied against the token the previous acknowledgement
 * returned, and once one is rejected the rest of that generation is discarded without ever reaching
 * the document.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EditorWorkspaceViewModelEditQueueTest : BaseTest() {

    private val workspaceId = Workspace.Id()
    private val epoch = Uuid.random()

    private fun token(version: Long) = EditorEngine.DocumentToken(epoch, version)

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

    private val emptySnapshot = EditorEngine.WindowSnapshot(
        content = EditorEngine.VisibleContent(),
        cursor = TextPosition.ZERO,
        selection = null,
    )

    private fun makeWorkspace(): EditorWorkspace = mockk<EditorWorkspace>().apply {
        every { info } returns MutableStateFlow(
            Workspace.Info(id = workspaceId, type = Workspace.Type.EDITOR, title = "test".toCaString()),
        )
        every { state } returns MutableStateFlow<EditorWorkspace.State>(
            EditorWorkspace.State.Ready(
                EditorWorkspace.EditorState(maxUndoableEditChars = 1_000_000L, windowToken = token(0)),
            ),
        )
        coEvery { performEdit(any(), any()) } answers { drained.complete(Unit); EditorEngine.EditOutcome.Applied() }
        coEvery { captureWindowSnapshot() } returns emptySnapshot
    }

    /** The text of an insert intent the ViewModel handed to the workspace. */
    private fun MockKAnswerScope<*, *>.insertedText(): String =
        (firstArg<EditorEngine.EditIntent>() as EditorEngine.EditIntent.InsertAtCursor).text

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

    private fun delta(
        text: String,
        generation: Long = 1L,
        snapshotToken: EditorEngine.DocumentToken? = null,
    ) = SessionDelta(
        start = TextPosition.ZERO,
        end = TextPosition.ZERO,
        oldText = "",
        newText = text,
        caret = TextPosition.ZERO,
        generation = generation,
        snapshotToken = snapshotToken,
    )

    @Test
    fun `a suspending edit cannot be overtaken by the next one`() = runTest {
        val applied = mutableListOf<String>()
        val newlineStarted = CompletableDeferred<Unit>()
        val releaseNewline = CompletableDeferred<Unit>()
        val workspace = makeWorkspace().apply {
            coEvery { applyFieldDelta(any()) } coAnswers {
                val text = firstArg<EditorEngine.FieldDelta>().newText
                if (text == "\n") {
                    newlineStarted.complete(Unit)
                    releaseNewline.await()
                }
                applied += text
                EditorEngine.MutationResult.Applied(token(applied.size.toLong()))
            }
        }
        val vm = makeViewModel(workspace)

        vm.enqueueFieldDelta(delta("\n", snapshotToken = token(0)))
        vm.enqueueFieldDelta(delta("X"))

        newlineStarted.await()
        releaseNewline.complete(Unit)
        vm.insertText("sentinel")
        drained.await()

        applied shouldBe listOf("\n", "X")
    }

    @Test
    fun `a second keystroke enqueued before a paste keeps its place in the queue`() = runTest {
        val applied = mutableListOf<String>()
        val firstStarted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val workspace = makeWorkspace().apply {
            coEvery { applyFieldDelta(any()) } coAnswers {
                val text = firstArg<EditorEngine.FieldDelta>().newText
                if (text == "a") {
                    firstStarted.complete(Unit)
                    releaseFirst.await()
                }
                applied += text
                EditorEngine.MutationResult.Applied(token(applied.size.toLong()))
            }
            coEvery { performEdit(any(), any()) } answers {
                val text = insertedText()
                applied += text
                if (text == "sentinel") drained.complete(Unit)
                EditorEngine.EditOutcome.Applied()
            }
        }
        val vm = makeViewModel(workspace)

        vm.enqueueFieldDelta(delta("a", snapshotToken = token(0)))
        firstStarted.await()
        // Typed while the first keystroke is still in flight, then a paste on top of it
        vm.enqueueFieldDelta(delta("b"))
        vm.insertText("pasted")
        releaseFirst.complete(Unit)
        vm.insertText("sentinel")
        drained.await()

        applied shouldBe listOf("a", "b", "pasted", "sentinel")
    }

    @Test
    fun `successors chain on the token their predecessor returned`() = runTest {
        val tokens = mutableListOf<EditorEngine.DocumentToken>()
        val workspace = makeWorkspace().apply {
            coEvery { applyFieldDelta(any()) } answers {
                val delta = firstArg<EditorEngine.FieldDelta>()
                tokens += delta.token
                EditorEngine.MutationResult.Applied(token(delta.token.structuralVersion + 1))
            }
        }
        val vm = makeViewModel(workspace)

        vm.enqueueFieldDelta(delta("a", snapshotToken = token(40))).await()
        vm.enqueueFieldDelta(delta("b")).await()
        vm.enqueueFieldDelta(delta("c")).await()

        tokens shouldBe listOf(token(40), token(41), token(42))
    }

    @Test
    fun `a conflict discards the rest of its generation without touching the document`() = runTest {
        val seen = mutableListOf<String>()
        val workspace = makeWorkspace().apply {
            coEvery { applyFieldDelta(any()) } answers {
                seen += firstArg<EditorEngine.FieldDelta>().newText
                EditorEngine.MutationResult.Conflict(emptySnapshot)
            }
        }
        val vm = makeViewModel(workspace)

        val first = vm.enqueueFieldDelta(delta("a", snapshotToken = token(40)))
        val second = vm.enqueueFieldDelta(delta("b"))

        first.await().shouldBeInstanceOf<EditorEngine.MutationResult.Conflict>()
        // The descendant completes as a conflict too, but never reaches the engine
        second.await().shouldBeInstanceOf<EditorEngine.MutationResult.Conflict>()
        seen shouldBe listOf("a")
    }

    @Test
    fun `a fresh generation after a conflict is applied again`() = runTest {
        val seen = mutableListOf<String>()
        val workspace = makeWorkspace().apply {
            coEvery { applyFieldDelta(any()) } answers {
                val delta = firstArg<EditorEngine.FieldDelta>()
                seen += delta.newText
                if (delta.newText == "a") {
                    EditorEngine.MutationResult.Conflict(emptySnapshot)
                } else {
                    EditorEngine.MutationResult.Applied(token(99))
                }
            }
        }
        val vm = makeViewModel(workspace)

        vm.enqueueFieldDelta(delta("a", generation = 1L, snapshotToken = token(40))).await()
        // The field rebuilt from the conflict snapshot and started a new lineage
        vm.enqueueFieldDelta(delta("b", generation = 2L, snapshotToken = token(50)))
            .await().shouldBeInstanceOf<EditorEngine.MutationResult.Applied>()

        seen shouldBe listOf("a", "b")
    }

    @Test
    fun `a delta with no token to chain on conflicts instead of guessing`() = runTest {
        val workspace = makeWorkspace().apply {
            coEvery { applyFieldDelta(any()) } returns EditorEngine.MutationResult.Applied(token(1))
        }
        val vm = makeViewModel(workspace)

        // No snapshot token and no predecessor: nothing to apply it against
        vm.enqueueFieldDelta(delta("x", generation = 5L))
            .await().shouldBeInstanceOf<EditorEngine.MutationResult.Conflict>()
    }

    // ==================== Clipboard operations ====================

    @Test
    fun `a cut's deletion is ordered against a later keystroke`() = runTest {
        // The clipboard write happens outside the queue, but the deletion it ends in is enqueued,
        // so a keystroke typed after it can't resolve against the not-yet-cut document.
        val applied = mutableListOf<String>()
        val deleteStarted = CompletableDeferred<Unit>()
        val releaseDelete = CompletableDeferred<Unit>()
        val workspace = makeWorkspace().apply {
            coEvery { prepareCut(any()) } returns Result.success(
                EditorEngine.CutSnapshot(text = "cut me", startOffset = 0L, token = token(1)),
            )
            coEvery { applyCut(any()) } coAnswers {
                deleteStarted.complete(Unit)
                releaseDelete.await()
                applied += "cut"
                Result.success("cut me")
            }
            coEvery { applyFieldDelta(any()) } answers {
                applied += firstArg<EditorEngine.FieldDelta>().newText
                EditorEngine.MutationResult.Applied(token(1))
            }
        }
        val vm = makeViewModel(workspace)

        vm.executeAction(EditorActionBarItem.Cut)
        deleteStarted.await()
        vm.enqueueFieldDelta(delta("X", snapshotToken = token(0)))
        releaseDelete.complete(Unit)
        vm.insertText("sentinel")
        drained.await()

        applied shouldBe listOf("cut", "X")
    }

    @Test
    fun `a paste's insertion goes through the queue`() = runTest {
        // File retrieval runs outside the queue, but the insert it produces is enqueued: while an
        // earlier edit is still running, the paste cannot reach the engine ahead of it.
        val applied = mutableListOf<String>()
        val readDone = CompletableDeferred<Unit>()
        val typingStarted = CompletableDeferred<Unit>()
        val releaseTyping = CompletableDeferred<Unit>()
        val workspace = makeWorkspace().apply {
            coEvery { readFileContent(any()) } coAnswers {
                readDone.complete(Unit)
                Result.success("pasted")
            }
            coEvery { performEdit(any(), any()) } answers {
                val text = insertedText()
                applied += text
                if (text == "sentinel") drained.complete(Unit)
                EditorEngine.EditOutcome.Applied()
            }
            coEvery { applyFieldDelta(any()) } coAnswers {
                typingStarted.complete(Unit)
                releaseTyping.await()
                applied += firstArg<EditorEngine.FieldDelta>().newText
                EditorEngine.MutationResult.Applied(token(1))
            }
        }
        val vm = makeViewModel(workspace)

        vm.enqueueFieldDelta(delta("X", snapshotToken = token(0)))
        typingStarted.await()

        vm.onPageAction(EditorPageAction.Clipboard.Paste(pathsClip("notes.txt")))
        readDone.await()
        // The file was read, but its insert waits behind the edit the consumer is still running
        applied.shouldBeEmpty()

        releaseTyping.complete(Unit)
        vm.insertText("sentinel")
        drained.await()

        applied shouldBe listOf("X", "pasted", "sentinel")
    }

    // ==================== Navigation ordering ====================

    @Test
    fun `a tap is ordered against the keystroke typed before it`() = runTest {
        // Navigation shares the queue, so a character typed before a tap can never be applied
        // after the caret moved.
        val order = mutableListOf<String>()
        val typingStarted = CompletableDeferred<Unit>()
        val releaseTyping = CompletableDeferred<Unit>()
        val workspace = makeWorkspace().apply {
            coEvery { applyFieldDelta(any()) } coAnswers {
                typingStarted.complete(Unit)
                releaseTyping.await()
                order += "type"
                EditorEngine.MutationResult.Applied(token(1))
            }
            coEvery { setCursorPosition(any()) } answers { order += "tap" }
            coEvery { performEdit(any(), any()) } answers {
                order += "sentinel"
                drained.complete(Unit)
                EditorEngine.EditOutcome.Applied()
            }
        }
        val vm = makeViewModel(workspace)

        vm.enqueueFieldDelta(delta("X", snapshotToken = token(0)))
        typingStarted.await()
        vm.setCursorPosition(TextPosition(offset = 0, line = 3, column = 2))
        releaseTyping.complete(Unit)
        vm.insertText("sentinel")
        drained.await()

        order shouldBe listOf("type", "tap", "sentinel")
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
