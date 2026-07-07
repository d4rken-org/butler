package eu.darken.butler.editor.ui.editor

import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.editor.core.EditorWorkspace
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.WorkspaceAction
import eu.darken.butler.workspace.core.WorkspaceEvent
import eu.darken.butler.workspace.core.WorkspaceProvider
import eu.darken.butler.workspace.core.WorkspaceRemote
import eu.darken.butler.common.ca.toCaString
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.coVerify
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
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import testhelpers.BaseTest
import testhelpers.coroutine.TestDispatcherProvider
import java.io.IOException

/**
 * Open-picker per-path dedup: picking an already-open file focuses the holding tab instead of
 * opening a duplicate, and the tab's own file is a strict no-op (re-opening it would load stale
 * disk content and then flush unsaved edits over it on old-engine release).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EditorWorkspaceViewModelOpenFileTest : BaseTest() {

    private val workspaceId = Workspace.Id()
    private val otherId = Workspace.Id()
    private val pathA = LocalPath.build("/test/a.txt")
    private val pathB = LocalPath.build("/test/b.txt")

    private val executed = mutableListOf<WorkspaceAction>()
    private val emitted = mutableListOf<WorkspaceEvent>()

    @BeforeEach
    fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        executed.clear()
        emitted.clear()
    }

    @AfterEach
    fun teardown() {
        Dispatchers.resetMain()
    }

    private fun makeWorkspace(contentPath: APath<*>?): EditorWorkspace = mockk<EditorWorkspace>().apply {
        every { info } returns MutableStateFlow(
            Workspace.Info(
                id = workspaceId,
                type = Workspace.Type.EDITOR,
                title = "test".toCaString(),
                contentPath = contentPath,
            )
        )
        every { state } returns MutableStateFlow<EditorWorkspace.State>(EditorWorkspace.State.Initializing)
        coEvery { openFile(any()) } returns Unit
    }

    private fun makeRemote(claimResult: WorkspaceAction.ClaimContentPath.Result): WorkspaceRemote = mockk {
        every { events } returns emptyFlow()
        every { state } returns emptyFlow()
        coEvery { emitEvent(any()) } coAnswers { emitted += firstArg<WorkspaceEvent>() }
        coEvery { execute(any()) } coAnswers {
            when (val action = firstArg<WorkspaceAction>()) {
                is WorkspaceAction.ClaimContentPath -> claimResult.also { executed += action }
                is WorkspaceAction.ReleaseContentPath -> WorkspaceAction.ReleaseContentPath.Result.also { executed += action }
                else -> throw IllegalStateException("Unexpected action: $action")
            }
        }
    }

    private fun makeViewModel(workspace: EditorWorkspace, remote: WorkspaceRemote): EditorWorkspaceViewModel {
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
    fun `picking the tab's own file is a no-op`() {
        val workspace = makeWorkspace(contentPath = pathA)
        val vm = makeViewModel(workspace, makeRemote(WorkspaceAction.ClaimContentPath.Result.Granted))

        vm.openFile(pathA)

        executed.shouldBeEmpty()
        emitted.shouldBeEmpty()
        coVerify(exactly = 0) { workspace.openFile(any()) }
    }

    @Test
    fun `picking a file held by another tab focuses it instead of opening`() {
        val workspace = makeWorkspace(contentPath = pathA)
        val remote = makeRemote(WorkspaceAction.ClaimContentPath.Result.AlreadyOpen(otherId))
        val vm = makeViewModel(workspace, remote)

        vm.openFile(pathB)

        coVerify(exactly = 0) { workspace.openFile(any()) }
        emitted.single().shouldBeInstanceOf<WorkspaceEvent.SelectionRequested>()
            .workspaceId shouldBe otherId
        // The path was never claimed by us, so nothing may be released
        executed.filterIsInstance<WorkspaceAction.ReleaseContentPath>().shouldBeEmpty()
    }

    @Test
    fun `granted claim opens the file and releases the claim afterwards`() {
        val workspace = makeWorkspace(contentPath = pathA)
        val vm = makeViewModel(workspace, makeRemote(WorkspaceAction.ClaimContentPath.Result.Granted))

        vm.openFile(pathB)

        coVerify(exactly = 1) { workspace.openFile(pathB) }
        val claim = executed.filterIsInstance<WorkspaceAction.ClaimContentPath>().single()
        claim.contentPath shouldBe pathB
        claim.claimantId shouldBe workspaceId
        val release = executed.filterIsInstance<WorkspaceAction.ReleaseContentPath>().single()
        release.contentPath shouldBe pathB
        release.claimantId shouldBe workspaceId
        executed.indexOf(claim) shouldBe 0
        executed.indexOf(release) shouldBe 1
    }

    @Test
    fun `re-picking a file that is already opening does not cancel the in-flight load`() {
        val infoFlow = MutableStateFlow(
            Workspace.Info(
                id = workspaceId,
                type = Workspace.Type.EDITOR,
                title = "test".toCaString(),
                contentPath = pathA,
            )
        )
        val gate = CompletableDeferred<Unit>()
        val workspace = mockk<EditorWorkspace> {
            every { info } returns infoFlow
            every { state } returns MutableStateFlow<EditorWorkspace.State>(EditorWorkspace.State.Initializing)
            coEvery { openFile(pathB) } coAnswers {
                // The real workspace publishes the target synchronously at the engine swap
                infoFlow.value = infoFlow.value.copy(contentPath = pathB)
                gate.await()
            }
        }
        val vm = makeViewModel(workspace, makeRemote(WorkspaceAction.ClaimContentPath.Result.Granted))

        vm.openFile(pathB)
        // Second pick of the same path while the first load is suspended: must be a no-op,
        // not a cancel-and-rollback of the only load
        vm.openFile(pathB)
        gate.complete(Unit)

        coVerify(exactly = 1) { workspace.openFile(pathB) }
        executed.filterIsInstance<WorkspaceAction.ClaimContentPath>() shouldHaveSize 1
        executed.filterIsInstance<WorkspaceAction.ReleaseContentPath>() shouldHaveSize 1
        emitted.shouldBeEmpty()
    }

    @Test
    fun `failed open still releases the claim`() {
        val workspace = makeWorkspace(contentPath = pathA)
        coEvery { workspace.openFile(pathB) } throws IOException("boom")
        val vm = makeViewModel(workspace, makeRemote(WorkspaceAction.ClaimContentPath.Result.Granted))

        vm.openFile(pathB)

        executed.filterIsInstance<WorkspaceAction.ReleaseContentPath>().single().contentPath shouldBe pathB
    }
}
