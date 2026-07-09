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
}
