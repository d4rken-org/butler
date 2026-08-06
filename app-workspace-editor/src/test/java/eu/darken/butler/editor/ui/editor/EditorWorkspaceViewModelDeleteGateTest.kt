package eu.darken.butler.editor.ui.editor

import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.editor.core.EditorWorkspace
import eu.darken.butler.editor.core.engine.EditorEngine
import eu.darken.butler.editor.core.engine.TextPosition
import eu.darken.butler.editor.ui.editor.text.SessionDelta
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.WorkspaceProvider
import eu.darken.butler.workspace.core.WorkspaceRemote
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
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
import kotlin.uuid.Uuid

/**
 * The large-edit gate as the ViewModel sees it: the ENGINE decides (it resolves the span atomically
 * with the operation) and hands back an immutable prepared edit; the ViewModel only stashes it,
 * shows the dialog, and submits it on confirm. A field delta is never gated - its span is
 * window-bounded and therefore always undoable.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EditorWorkspaceViewModelDeleteGateTest : BaseTest() {

    private val workspaceId = Workspace.Id()
    private val threshold = 1_000_000L
    private val epoch = Uuid.random()
    private val token = EditorEngine.DocumentToken(epoch, structuralVersion = 7L)

    @BeforeEach
    fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterEach
    fun teardown() {
        Dispatchers.resetMain()
    }

    private fun prepared(selection: Pair<TextPosition, TextPosition>, replacement: String) =
        EditorEngine.PreparedMutation(
            token = token,
            startOffset = minOf(selection.first.offset, selection.second.offset),
            endOffset = maxOf(selection.first.offset, selection.second.offset),
            replacement = replacement,
        )

    /**
     * Engine stand-in: gates exactly like [EditorEngine.oversizedGate] - the selection's own span,
     * measured absolute, strictly above the threshold.
     */
    private fun makeWorkspace(selection: Pair<TextPosition, TextPosition>?): EditorWorkspace =
        mockk<EditorWorkspace>().apply {
            every { info } returns MutableStateFlow(
                Workspace.Info(id = workspaceId, type = Workspace.Type.EDITOR, title = "test".toCaString()),
            )
            every { state } returns MutableStateFlow<EditorWorkspace.State>(
                EditorWorkspace.State.Ready(
                    EditorWorkspace.EditorState(
                        selectionRange = selection,
                        maxUndoableEditChars = threshold,
                    ),
                ),
            )
            val oversized = selection != null &&
                kotlin.math.abs(selection.second.offset - selection.first.offset) > threshold

            fun gated(replacement: String): EditorEngine.EditOutcome = when {
                oversized -> EditorEngine.EditOutcome.RequiresConfirmation(prepared(selection!!, replacement))
                else -> EditorEngine.EditOutcome.Applied()
            }

            coEvery { deleteSelection() } answers { gated("") }
            coEvery { deleteAtCursor(any()) } answers { gated("") }
            coEvery { deleteForward() } answers { gated("") }
            coEvery { insertText(any()) } answers { gated(firstArg()) }
            coEvery { applyFieldDelta(any()) } returns EditorEngine.MutationResult.Applied(token)
            coEvery { submitPrepared(any()) } returns EditorEngine.MutationResult.Applied(token)
        }

    private val hugeSelection = TextPosition(0, 0, 0) to TextPosition(threshold + 1, 0, 0)
    private val exactSelection = TextPosition(0, 0, 0) to TextPosition(threshold, 0, 0)
    private val smallSelection = TextPosition(0, 0, 0) to TextPosition(500, 0, 500)

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
    fun `above threshold shows confirm dialog and applies nothing`() = runTest {
        val workspace = makeWorkspace(hugeSelection)
        val vm = makeViewModel(workspace)

        vm.requestDeleteSelection()

        vm.state.first().showLargeDeleteConfirmDialog shouldBe true
        coVerify(exactly = 0) { workspace.submitPrepared(any()) }
    }

    @Test
    fun `below threshold deletes directly without a dialog`() = runTest {
        val workspace = makeWorkspace(smallSelection)
        val vm = makeViewModel(workspace)

        vm.requestDeleteSelection()

        vm.state.first().showLargeDeleteConfirmDialog shouldBe false
        coVerify(exactly = 1) { workspace.deleteSelection() }
    }

    @Test
    fun `a selection of exactly the threshold is not gated`() = runTest {
        // The gate triggers on span > threshold: an edit AT the budget is still undoable
        val workspace = makeWorkspace(exactSelection)
        val vm = makeViewModel(workspace)

        vm.requestDeleteSelection()

        vm.state.first().showLargeDeleteConfirmDialog shouldBe false
    }

    @Test
    fun `reversed huge selection still confirms`() = runTest {
        // start > end: the size test must be absolute, else the count is negative and skips the gate
        val workspace = makeWorkspace(TextPosition(threshold + 1, 0, 0) to TextPosition(0, 0, 0))
        val vm = makeViewModel(workspace)

        vm.requestDeleteSelection()

        vm.state.first().showLargeDeleteConfirmDialog shouldBe true
    }

    @Test
    fun `confirming the dialog submits exactly the prepared edit and dismisses`() = runTest {
        val workspace = makeWorkspace(hugeSelection)
        val vm = makeViewModel(workspace)

        vm.requestDeleteSelection()
        vm.confirmLargeDelete()

        vm.state.first().showLargeDeleteConfirmDialog shouldBe false
        coVerify(exactly = 1) { workspace.submitPrepared(prepared(hugeSelection, "")) }
    }

    @Test
    fun `a confirmed edit whose document moved on mutates nothing`() = runTest {
        val workspace = makeWorkspace(hugeSelection).apply {
            coEvery { submitPrepared(any()) } returns EditorEngine.MutationResult.Conflict(
                EditorEngine.WindowSnapshot(EditorEngine.VisibleContent(), TextPosition.ZERO, null),
            )
        }
        val vm = makeViewModel(workspace)

        vm.requestDeleteSelection()
        vm.confirmLargeDelete()

        // The stale token is rejected inside the engine; the ViewModel just closes the dialog
        vm.state.first().showLargeDeleteConfirmDialog shouldBe false
        coVerify(exactly = 1) { workspace.submitPrepared(any()) }
    }

    @Test
    fun `backspace over a huge selection confirms instead of deleting`() = runTest {
        val workspace = makeWorkspace(hugeSelection)
        val vm = makeViewModel(workspace)

        vm.deleteAtCursor(1)

        vm.state.first().showLargeDeleteConfirmDialog shouldBe true
        coVerify(exactly = 0) { workspace.submitPrepared(any()) }
    }

    @Test
    fun `forward-delete over a huge selection confirms instead of deleting`() = runTest {
        val workspace = makeWorkspace(hugeSelection)
        val vm = makeViewModel(workspace)

        vm.deleteForward()

        vm.state.first().showLargeDeleteConfirmDialog shouldBe true
        coVerify(exactly = 0) { workspace.submitPrepared(any()) }
    }

    @Test
    fun `plain backspace without a selection is not gated`() = runTest {
        val workspace = makeWorkspace(selection = null)
        val vm = makeViewModel(workspace)

        vm.deleteAtCursor(1)

        vm.state.first().showLargeDeleteConfirmDialog shouldBe false
        coVerify(exactly = 1) { workspace.deleteAtCursor(1) }
    }

    // ==================== Field deltas are never gated ====================

    @Test
    fun `typing over a huge selection replaces directly instead of confirming`() = runTest {
        // A field delta only ever replaces a window-bounded range, which is always undoable, so
        // the gate must not fire for it - deliberate change from the previous selection-based gate.
        val workspace = makeWorkspace(hugeSelection)
        val vm = makeViewModel(workspace)

        vm.enqueueFieldDelta(fieldDelta("x")).await()

        vm.state.first().showLargeDeleteConfirmDialog shouldBe false
        coVerify(exactly = 1) { workspace.applyFieldDelta(any()) }
    }

    // ==================== Paste / stash lifecycle ====================

    @Test
    fun `insert over a huge selection defers behind the dialog`() = runTest {
        val workspace = makeWorkspace(hugeSelection)
        val vm = makeViewModel(workspace)

        vm.insertText("x")

        vm.state.first().showLargeDeleteConfirmDialog shouldBe true
        coVerify(exactly = 0) { workspace.submitPrepared(any()) }
    }

    @Test
    fun `dismissing clears the pending edit so a later confirm is a no-op`() = runTest {
        val workspace = makeWorkspace(hugeSelection)
        val vm = makeViewModel(workspace)

        vm.insertText("x")
        vm.onPageAction(EditorPageAction.Dialog.DismissLargeDeleteConfirm)
        vm.confirmLargeDelete()

        vm.state.first().showLargeDeleteConfirmDialog shouldBe false
        coVerify(exactly = 0) { workspace.submitPrepared(any()) }
    }

    @Test
    fun `a second gated edit while pending is dropped (first-writer-wins)`() = runTest {
        val workspace = makeWorkspace(hugeSelection)
        val vm = makeViewModel(workspace)

        vm.insertText("first")
        vm.insertText("second")
        vm.confirmLargeDelete()

        // The first edit is the one the user reviewed; the second must not replace it.
        coVerify(exactly = 1) { workspace.submitPrepared(prepared(hugeSelection, "first")) }
        coVerify(exactly = 0) { workspace.submitPrepared(prepared(hugeSelection, "second")) }
    }

    private fun fieldDelta(text: String) = SessionDelta(
        start = TextPosition.ZERO,
        end = TextPosition.ZERO,
        oldText = "",
        newText = text,
        caret = TextPosition.ZERO,
        generation = 1L,
        snapshotToken = token,
    )
}
