package eu.darken.butler.saver.ui.saver

import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.actions.PathActionIssue
import eu.darken.butler.common.files.local.LocalPathLookup
import eu.darken.butler.common.files.metadata.FileType
import eu.darken.butler.common.flow.SingleEventFlow
import eu.darken.butler.common.issue.Issue
import eu.darken.butler.saver.core.SaverWorkspace
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.WorkspaceProvider
import eu.darken.butler.workspace.core.WorkspaceRemote
import eu.darken.butler.workspace.core.operations.Operation
import eu.darken.butler.workspace.core.operations.OperationFocusRequest
import eu.darken.butler.workspace.ui.page.WorkspacePageChrome
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
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
 * Auto-surfacing the file-conflict sheet: only a NEW conflict, only in the modal (APK-export) path.
 * Drives [SaverWorkspaceViewModel.autoSurfaceModalConflicts] directly (the Host adds the
 * focus + RESUMED gate that this test can't reproduce off-device).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SaverWorkspaceConflictSheetTest : BaseTest() {

    private val workspaceId = Workspace.Id()
    private val pendingConflicts = MutableStateFlow<Map<Operation.Id, Issue>>(emptyMap())

    @BeforeEach
    fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterEach
    fun teardown() {
        Dispatchers.resetMain()
    }

    private fun pathExistsIssue() = PathActionIssue.PathAlreadyExists(
        destination = LocalPathLookup(
            lookedUp = LocalPath.build("/save/app.apk"),
            fileType = FileType.FILE,
            size = 4L,
            modifiedAt = null,
        ),
        canOverwrite = true,
        canSkip = true,
    )

    private fun permissionIssue() = PathActionIssue.InsufficientPermission(
        destinationPath = LocalPath.build("/save/app.apk"),
    )

    private fun makeWorkspace(modal: Boolean) = mockk<SaverWorkspace>().apply {
        // autoSurface reads the immutable marker, not state (which seeds with a null caller id).
        every { callerWorkspaceId } returns if (modal) Workspace.Id() else null
        every { state } returns MutableStateFlow(
            SaverWorkspace.State(callerWorkspaceId = if (modal) Workspace.Id() else null),
        )
        every { currentOperation } returns flowOf(null)
        every { resolveConflict(any(), any()) } returns Unit
    }

    private fun makeViewModel(workspace: SaverWorkspace): SaverWorkspaceViewModel {
        val chrome = mockk<WorkspacePageChrome>(relaxed = true).apply {
            every { pendingConflicts } returns this@SaverWorkspaceConflictSheetTest.pendingConflicts
            every { shareIntentEvent } returns SingleEventFlow()
        }
        val chromeFactory = mockk<WorkspacePageChrome.Factory>().apply {
            every { create(any(), any<CoroutineScope>()) } returns chrome
        }
        val remote = mockk<WorkspaceRemote> {
            every { events } returns emptyFlow()
            every { state } returns emptyFlow()
        }
        val provider = mockk<WorkspaceProvider> {
            every { retrieve(workspaceId) } returns flowOf(workspace)
        }
        return SaverWorkspaceViewModel(
            id = workspaceId,
            dispatchers = TestDispatcherProvider(),
            workspaceProvider = provider,
            workspaceRemote = remote,
            storageEnvironment = mockk(relaxed = true),
            operationFocusRequest = OperationFocusRequest(),
            chromeFactory = chromeFactory,
        )
    }

    @Test
    fun `modal - a new conflict auto-opens the sheet`() = runTest(UnconfinedTestDispatcher()) {
        val vm = makeViewModel(makeWorkspace(modal = true))
        backgroundScope.launch { vm.autoSurfaceModalConflicts() }

        val issue = pathExistsIssue()
        pendingConflicts.value = mapOf(Operation.Id() to issue)

        vm.conflictUiState.value.visible shouldBe true
        vm.conflictUiState.value.issue shouldBe issue
    }

    @Test
    fun `modal - a conflict already present when the page becomes eligible does NOT auto-open`() =
        runTest(UnconfinedTestDispatcher()) {
            val vm = makeViewModel(makeWorkspace(modal = true))

            // Conflict exists BEFORE we start collecting (e.g. it arose while backgrounded).
            val issue = pathExistsIssue()
            pendingConflicts.value = mapOf(Operation.Id() to issue)

            backgroundScope.launch { vm.autoSurfaceModalConflicts() }

            // Tracked (row/manual-tap), but not surfaced: baseline is dropped.
            vm.conflictUiState.value.issue shouldBe issue
            vm.conflictUiState.value.visible shouldBe false
        }

    @Test
    fun `non-modal - a new conflict never auto-opens`() = runTest(UnconfinedTestDispatcher()) {
        val vm = makeViewModel(makeWorkspace(modal = false))
        backgroundScope.launch { vm.autoSurfaceModalConflicts() }

        pendingConflicts.value = mapOf(Operation.Id() to pathExistsIssue())

        vm.conflictUiState.value.visible shouldBe false
    }

    @Test
    fun `modal - a non-file-exists waiting issue does NOT auto-open`() =
        runTest(UnconfinedTestDispatcher()) {
            val vm = makeViewModel(makeWorkspace(modal = true))
            backgroundScope.launch { vm.autoSurfaceModalConflicts() }

            pendingConflicts.value = mapOf(Operation.Id() to permissionIssue())

            vm.conflictUiState.value.visible shouldBe false
        }

    @Test
    fun `dismiss hides the sheet and re-entering the eligible state does not re-open it`() =
        runTest(UnconfinedTestDispatcher()) {
            val vm = makeViewModel(makeWorkspace(modal = true))
            val collection = backgroundScope.launch { vm.autoSurfaceModalConflicts() }

            val issue = pathExistsIssue()
            pendingConflicts.value = mapOf(Operation.Id() to issue)
            vm.conflictUiState.value.visible shouldBe true

            vm.dismissConflictSheet()
            vm.conflictUiState.value.visible shouldBe false
            // Identity kept so the waiting row stays re-tappable.
            vm.conflictUiState.value.issue shouldBe issue

            // Simulate leaving + re-entering focus/RESUMED: the collector restarts. The still-pending
            // conflict is the restart's baseline (drop(1)), so it must NOT re-open.
            collection.cancel()
            backgroundScope.launch { vm.autoSurfaceModalConflicts() }

            vm.conflictUiState.value.visible shouldBe false
        }

    @Test
    fun `resolve clears the sheet when it still shows the resolved conflict`() =
        runTest(UnconfinedTestDispatcher()) {
            val workspace = makeWorkspace(modal = true)
            // Resolving removes the conflict from the pending set (op leaves Waiting).
            every { workspace.resolveConflict(any(), any()) } answers { pendingConflicts.value = emptyMap() }
            val vm = makeViewModel(workspace)
            backgroundScope.launch { vm.autoSurfaceModalConflicts() }

            pendingConflicts.value = mapOf(Operation.Id() to pathExistsIssue())
            vm.conflictUiState.value.visible shouldBe true

            vm.resolveConflict(PathActionIssue.PathAlreadyExists.Resolution.Overwrite())

            vm.conflictUiState.value.visible shouldBe false
            vm.conflictUiState.value.issue shouldBe null
        }

    @Test
    fun `resolve does NOT clobber a fast next-file conflict`() = runTest(UnconfinedTestDispatcher()) {
        val issueB = pathExistsIssue()
        val opB = Operation.Id()
        val workspace = makeWorkspace(modal = true)
        // Resolving A immediately surfaces the next file's conflict B.
        every { workspace.resolveConflict(any(), any()) } answers { pendingConflicts.value = mapOf(opB to issueB) }
        val vm = makeViewModel(workspace)
        backgroundScope.launch { vm.autoSurfaceModalConflicts() }

        val issueA = pathExistsIssue()
        pendingConflicts.value = mapOf(Operation.Id() to issueA)
        vm.conflictUiState.value.issue shouldBe issueA

        vm.resolveConflict(PathActionIssue.PathAlreadyExists.Resolution.Overwrite())

        // Conflict B must remain shown, not cleared by A's resolution.
        vm.conflictUiState.value.issue shouldBe issueB
        vm.conflictUiState.value.visible shouldBe true
    }
}
