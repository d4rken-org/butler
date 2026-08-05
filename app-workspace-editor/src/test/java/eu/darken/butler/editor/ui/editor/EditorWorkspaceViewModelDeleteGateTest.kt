package eu.darken.butler.editor.ui.editor

import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.editor.core.EditorWorkspace
import eu.darken.butler.editor.core.engine.TextPosition
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

/**
 * The action-bar Delete gate: a selection larger than the (non-undoable) threshold must confirm
 * before deleting; a smaller one deletes directly. The size test is ABSOLUTE, so a reversed
 * selection is measured correctly.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EditorWorkspaceViewModelDeleteGateTest : BaseTest() {

    private val workspaceId = Workspace.Id()
    private val threshold = 1_000_000L

    @BeforeEach
    fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterEach
    fun teardown() {
        Dispatchers.resetMain()
    }

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
            coEvery { deleteSelection() } returns Result.success("")
            coEvery { deleteAtCursor(any()) } returns Result.success("")
            coEvery { deleteForward() } returns Unit
            coEvery { replaceText(any(), any(), any(), any()) } returns true
            coEvery { insertText(any()) } returns Unit
            coEvery { selectionExceedsUndoThreshold() } returns (
                selection != null &&
                    kotlin.math.abs(selection.second.offset - selection.first.offset) > threshold
                )
        }

    private val hugeSelection = TextPosition(0, 0, 0) to TextPosition(threshold + 1, 0, 0)
    private val smallSelection = TextPosition(0, 0, 0) to TextPosition(500, 0, 500)
    private val pos = TextPosition(0, 0, 0)

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
    fun `above threshold shows confirm dialog and does not delete`() = runTest {
        val workspace = makeWorkspace(TextPosition(0, 0, 0) to TextPosition(threshold + 1, 0, 0))
        val vm = makeViewModel(workspace)

        vm.requestDeleteSelection()

        vm.state.first().showLargeDeleteConfirmDialog shouldBe true
        coVerify(exactly = 0) { workspace.deleteSelection() }
    }

    @Test
    fun `below threshold deletes directly without a dialog`() = runTest {
        val workspace = makeWorkspace(TextPosition(0, 0, 0) to TextPosition(500, 0, 500))
        val vm = makeViewModel(workspace)

        vm.requestDeleteSelection()

        vm.state.first().showLargeDeleteConfirmDialog shouldBe false
        coVerify(exactly = 1) { workspace.deleteSelection() }
    }

    @Test
    fun `reversed huge selection still confirms`() = runTest {
        // start > end: the size test must be absolute, else the count is negative and skips the gate
        val workspace = makeWorkspace(TextPosition(threshold + 1, 0, 0) to TextPosition(0, 0, 0))
        val vm = makeViewModel(workspace)

        vm.requestDeleteSelection()

        vm.state.first().showLargeDeleteConfirmDialog shouldBe true
        coVerify(exactly = 0) { workspace.deleteSelection() }
    }

    @Test
    fun `confirming the dialog deletes and dismisses`() = runTest {
        val workspace = makeWorkspace(TextPosition(0, 0, 0) to TextPosition(threshold + 1, 0, 0))
        val vm = makeViewModel(workspace)

        vm.requestDeleteSelection()
        vm.confirmLargeDelete()

        vm.state.first().showLargeDeleteConfirmDialog shouldBe false
        coVerify(exactly = 1) { workspace.deleteSelection() }
    }

    @Test
    fun `backspace over a huge selection confirms instead of deleting`() = runTest {
        val workspace = makeWorkspace(TextPosition(0, 0, 0) to TextPosition(threshold + 1, 0, 0))
        val vm = makeViewModel(workspace)

        vm.deleteAtCursor(1)

        vm.state.first().showLargeDeleteConfirmDialog shouldBe true
        coVerify(exactly = 0) { workspace.deleteAtCursor(any()) }
    }

    @Test
    fun `forward-delete over a huge selection confirms instead of deleting`() = runTest {
        val workspace = makeWorkspace(TextPosition(0, 0, 0) to TextPosition(threshold + 1, 0, 0))
        val vm = makeViewModel(workspace)

        vm.deleteForward()

        vm.state.first().showLargeDeleteConfirmDialog shouldBe true
        coVerify(exactly = 0) { workspace.deleteForward() }
    }

    @Test
    fun `plain backspace without a selection is not gated`() = runTest {
        val workspace = makeWorkspace(selection = null)
        val vm = makeViewModel(workspace)

        vm.deleteAtCursor(1)

        vm.state.first().showLargeDeleteConfirmDialog shouldBe false
        coVerify(exactly = 1) { workspace.deleteAtCursor(1) }
    }

    // ==================== Replace / typing over a huge selection ====================

    @Test
    fun `typing over a huge selection confirms instead of replacing`() = runTest {
        val workspace = makeWorkspace(hugeSelection)
        val vm = makeViewModel(workspace)

        vm.replaceText(pos, pos, "x", pos)

        val state = vm.state.first()
        state.showLargeDeleteConfirmDialog shouldBe true
        // Field-originated gate must bump the resync signal so the hidden field reverts.
        state.editResyncSignal shouldBe 1
        coVerify(exactly = 0) { workspace.replaceText(any(), any(), any(), any()) }
    }

    @Test
    fun `confirming a huge type-over replays the replace`() = runTest {
        val workspace = makeWorkspace(hugeSelection)
        val vm = makeViewModel(workspace)

        vm.replaceText(pos, pos, "x", pos)
        vm.confirmLargeDelete()

        vm.state.first().showLargeDeleteConfirmDialog shouldBe false
        coVerify(exactly = 1) { workspace.replaceText(pos, pos, "x", pos) }
    }

    @Test
    fun `typing below the threshold replaces directly`() = runTest {
        val workspace = makeWorkspace(smallSelection)
        val vm = makeViewModel(workspace)

        vm.replaceText(pos, pos, "x", pos)

        val state = vm.state.first()
        state.showLargeDeleteConfirmDialog shouldBe false
        state.editResyncSignal shouldBe 0
        coVerify(exactly = 1) { workspace.replaceText(pos, pos, "x", pos) }
    }

    @Test
    fun `insert over a huge selection defers without bumping the field resync`() = runTest {
        val workspace = makeWorkspace(hugeSelection)
        val vm = makeViewModel(workspace)

        // Paste-style insert is programmatic (not field-originated): gated, but no resync bump.
        vm.insertText("x")

        val state = vm.state.first()
        state.showLargeDeleteConfirmDialog shouldBe true
        state.editResyncSignal shouldBe 0
        coVerify(exactly = 0) { workspace.insertText(any()) }
    }

    @Test
    fun `dismissing clears the pending edit so a later confirm is a no-op`() = runTest {
        val workspace = makeWorkspace(hugeSelection)
        val vm = makeViewModel(workspace)

        vm.replaceText(pos, pos, "x", pos)
        vm.onPageAction(EditorPageAction.Dialog.DismissLargeDeleteConfirm)
        vm.confirmLargeDelete()

        vm.state.first().showLargeDeleteConfirmDialog shouldBe false
        coVerify(exactly = 0) { workspace.replaceText(any(), any(), any(), any()) }
    }

    @Test
    fun `a second gated edit while pending is dropped (first-writer-wins)`() = runTest {
        val workspace = makeWorkspace(hugeSelection)
        val vm = makeViewModel(workspace)

        vm.replaceText(pos, pos, "first", pos)
        vm.replaceText(pos, pos, "second", pos)
        vm.confirmLargeDelete()

        // The first edit is the one the user reviewed; the second must not replace it.
        coVerify(exactly = 1) { workspace.replaceText(pos, pos, "first", pos) }
        coVerify(exactly = 0) { workspace.replaceText(pos, pos, "second", pos) }
    }

    @Test
    fun `a delete gate does not bump the field resync signal`() = runTest {
        val workspace = makeWorkspace(hugeSelection)
        val vm = makeViewModel(workspace)

        vm.requestDeleteSelection()

        val state = vm.state.first()
        state.showLargeDeleteConfirmDialog shouldBe true
        state.editResyncSignal shouldBe 0
    }
}
