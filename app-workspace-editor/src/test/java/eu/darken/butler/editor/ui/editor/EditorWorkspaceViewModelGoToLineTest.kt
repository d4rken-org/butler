package eu.darken.butler.editor.ui.editor

import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.editor.core.EditorWorkspace
import eu.darken.butler.editor.core.engine.TextPosition
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.WorkspaceProvider
import eu.darken.butler.workspace.core.WorkspaceRemote
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
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
 * Go to line must scroll even when the target is the line the cursor already sits on: the engine
 * republishes an equal cursor, which a StateFlow drops, so the cursor alone can't drive the scroll.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EditorWorkspaceViewModelGoToLineTest : BaseTest() {

    private val workspaceId = Workspace.Id()

    @BeforeEach
    fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterEach
    fun teardown() {
        Dispatchers.resetMain()
    }

    private val wsState = MutableStateFlow<EditorWorkspace.State>(
        EditorWorkspace.State.Ready(
            EditorWorkspace.EditorState(
                totalLines = 1_000L,
                cursorPosition = TextPosition(offset = 4_000L, line = 400L, column = 0),
            ),
        ),
    )

    private val goToLineDone = CompletableDeferred<Unit>()

    /** Mirrors the engine: the cursor lands at column 0 of the target line, published via a StateFlow. */
    private fun makeWorkspace(): EditorWorkspace = mockk<EditorWorkspace>().apply {
        every { info } returns MutableStateFlow(
            Workspace.Info(id = workspaceId, type = Workspace.Type.EDITOR, title = "test".toCaString()),
        )
        every { state } returns wsState
        coEvery { goToLine(any()) } answers {
            val line = firstArg<Long>()
            wsState.update { current ->
                val ready = current as EditorWorkspace.State.Ready
                ready.copy(
                    editor = ready.editor.copy(
                        cursorPosition = TextPosition(offset = line * 10L, line = line, column = 0),
                    ),
                )
            }
            goToLineDone.complete(Unit)
            Result.success(Unit)
        }
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
    fun `go to the cursor's own line still requests a scroll`() = runTest {
        val workspace = makeWorkspace()
        val vm = makeViewModel(workspace)
        val before = vm.state.first()
        before.cursorPosition.line shouldBe 400L

        vm.onPageAction(EditorPageAction.Navigation.GoToLine(400L))
        goToLineDone.await()

        coVerify(exactly = 1) { workspace.goToLine(400L) }
        val after = vm.state.first()
        after.cursorPosition shouldBe before.cursorPosition
        withClue("scrollTrigger must change so LazyTextEditor re-runs its scroll effect") {
            after.scrollTrigger shouldNotBe before.scrollTrigger
        }
    }

    @Test
    fun `go to a different line moves the cursor`() = runTest {
        val workspace = makeWorkspace()
        val vm = makeViewModel(workspace)

        vm.onPageAction(EditorPageAction.Navigation.GoToLine(12L))
        goToLineDone.await()

        vm.state.first().cursorPosition shouldBe TextPosition(offset = 120L, line = 12L, column = 0)
    }
}
