package eu.darken.butler.workspace.ui.workspaces

import androidx.lifecycle.SavedStateHandle
import eu.darken.butler.common.WebpageTool
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.compose.tour.GuidedTourController
import eu.darken.butler.common.datastore.DataStoreValue
import eu.darken.butler.common.debug.bugreport.BugReportRepo
import eu.darken.butler.common.error.ErrorReportTool
import eu.darken.butler.common.review.ReviewTool
import eu.darken.butler.main.core.motd.MotdRepo
import eu.darken.butler.upgrade.UpgradeRepo
import eu.darken.butler.workspace.core.OpenInNewTabsUseCase
import eu.darken.butler.workspace.core.PendingWorkspaceConfirmation
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.WorkspaceRemote
import eu.darken.butler.workspace.core.WorkspaceRepo
import eu.darken.butler.workspace.core.WorkspaceSettings
import eu.darken.butler.workspace.ui.WorkspacePageManager
import eu.darken.butler.workspace.ui.WorkspaceVisibilityTracker
import eu.darken.butler.workspace.ui.dialogs.ManagerDialog
import eu.darken.butler.workspace.ui.dialogs.ManagerDialogAction
import eu.darken.butler.workspace.ui.floatingbar.WorkspaceBarCollapseStates
import eu.darken.butler.workspace.ui.scroll.WorkspaceScrollPositions
import eu.darken.butler.workspace.ui.session.WorkspaceSessionManager
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import testhelpers.BaseTest
import testhelpers.TestApplication
import testhelpers.coroutine.TestDispatcherProvider
import testhelpers.coroutine.runTest2
import kotlin.time.Duration.Companion.seconds

/**
 * Who hosts a close confirmation and what it acts on are two different workspaces, and the dialog
 * actions have to keep them apart.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApplication::class)
class WorkspacesViewModelDialogTest : BaseTest() {

    private val testDispatcher = StandardTestDispatcher()
    private val workspaceRepo = mockk<WorkspaceRepo>(relaxed = true)
    private val pageManager = mockk<WorkspacePageManager>(relaxed = true)

    @Before fun setup() {
        Dispatchers.setMain(testDispatcher)
    }

    @After fun teardown() {
        Dispatchers.resetMain()
    }

    private fun boolSetting(value: Boolean): DataStoreValue<Boolean> =
        mockk<DataStoreValue<Boolean>>(relaxed = true).apply {
            every { flow } returns flowOf(value)
        }

    private fun vm(
        infos: List<Workspace.Info> = emptyList(),
        confirmations: Map<String, PendingWorkspaceConfirmation> = emptyMap(),
        focusedWorkspaceId: Workspace.Id? = null,
    ): WorkspacesViewModel {
        every { workspaceRepo.state } returns flowOf(WorkspaceRemote.State(infos = infos))
        every { workspaceRepo.events } returns emptyFlow()
        every { workspaceRepo.pendingConfirmations } returns flowOf(confirmations)

        val workspaceSettings = mockk<WorkspaceSettings>(relaxed = true).apply {
            every { swipeGesturesEnabled } returns boolSetting(true)
            every { onDemandWorkspaceCreation } returns boolSetting(true)
            every { paneClickToFocus } returns boolSetting(true)
        }
        every { pageManager.state } returns MutableStateFlow(
            WorkspacePageManager.State(focusedWorkspaceId = focusedWorkspaceId),
        )
        val sessionManager = mockk<WorkspaceSessionManager>(relaxed = true).apply {
            every { state } returns MutableStateFlow(WorkspaceSessionManager.State.Disabled)
        }
        val proInfo = mockk<UpgradeRepo.Info>(relaxed = true).apply {
            every { isPro } returns true
        }
        val upgradeRepo = mockk<UpgradeRepo>(relaxed = true).apply {
            every { upgradeInfo } returns flowOf(proInfo)
        }
        val motdRepo = mockk<MotdRepo>(relaxed = true).apply {
            every { motd } returns flowOf(null)
        }
        val bugReportRepo = mockk<BugReportRepo>(relaxed = true).apply {
            every { hasUnseenCrashes } returns flowOf(false)
        }
        val reviewTool = mockk<ReviewTool>(relaxed = true).apply {
            every { state } returns flowOf(ReviewTool.State())
        }
        val guidedTourController = mockk<GuidedTourController>(relaxed = true).apply {
            every { session } returns MutableStateFlow(null)
        }

        return WorkspacesViewModel(
            dispatchers = TestDispatcherProvider(),
            upgradeRepo = upgradeRepo,
            workspaceRepo = workspaceRepo,
            workspaceSettings = workspaceSettings,
            savedStateHandle = SavedStateHandle(),
            workspacePageManager = pageManager,
            sessionManager = sessionManager,
            motdRepo = motdRepo,
            webpageTool = mockk<WebpageTool>(relaxed = true),
            errorReportTool = mockk<ErrorReportTool>(relaxed = true),
            bugReportRepo = bugReportRepo,
            openInNewTabsUseCase = mockk<OpenInNewTabsUseCase>(relaxed = true),
            reviewTool = reviewTool,
            guidedTourController = guidedTourController,
            pageHosts = emptyMap(),
            scrollPositions = mockk<WorkspaceScrollPositions>(relaxed = true),
            barCollapseStates = mockk<WorkspaceBarCollapseStates>(relaxed = true),
            pagerVisibility = WorkspaceVisibilityTracker(),
        )
    }

    private suspend fun WorkspacesViewModel.settledDialogs() =
        withTimeoutOrNull(10.seconds) { managerDialogs.filterNotNull().first { it.isNotEmpty() } }

    private fun closeConfirmation(
        id: String,
        closing: Workspace.Id,
        host: Workspace.Id?,
    ) = id to PendingWorkspaceConfirmation(
        id = id,
        sourceWorkspaceId = host,
        data = PendingWorkspaceConfirmation.ConfirmationData.WorkspaceCloseConfirmation(
            workspaceId = closing,
            workspaceTitle = "notes.txt".toCaString(),
            hasUnsavedChanges = true,
        ),
    )

    @Test fun `the mapping keeps the closing tab and its host apart`() = runTest2(context = testDispatcher) {
        val editor = Workspace.Id()
        val host = Workspace.Id()
        val vm = vm(confirmations = mapOf(closeConfirmation("c1", closing = editor, host = host)))
        advanceUntilIdle()

        val dialog = vm.settledDialogs()!!.single()
            .shouldBeInstanceOf<ManagerDialog.WorkspaceTargeted.CloseConfirmation>()
        dialog.closingWorkspaceId shouldBe editor
        dialog.targetWorkspaceId shouldBe host
    }

    @Test fun `a confirmation without a host falls back to the focused workspace`() =
        runTest2(context = testDispatcher) {
            val editor = Workspace.Id()
            val focused = Workspace.Id()
            val vm = vm(
                confirmations = mapOf(closeConfirmation("c1", closing = editor, host = null)),
                focusedWorkspaceId = focused,
            )
            advanceUntilIdle()

            val dialog = vm.settledDialogs()!!.single()
                .shouldBeInstanceOf<ManagerDialog.WorkspaceTargeted.CloseConfirmation>()
            dialog.closingWorkspaceId shouldBe editor
            dialog.targetWorkspaceId shouldBe focused
        }

    @Test fun `resolving acts on the confirmation id it was given`() = runTest2(context = testDispatcher) {
        val vm = vm()

        vm.executeScreenAction(
            WorkspaceScreenAction.HandleDialog(ManagerDialogAction.Resolve("c1", confirmed = true)),
        )
        advanceUntilIdle()

        verify(exactly = 1) { workspaceRepo.resolveConfirmation("c1", true) }
    }

    @Test fun `going to a tab resolves the confirmation before the selection lands`() =
        runTest2(context = testDispatcher) {
            val editor = Workspace.Id()
            val host = Workspace.Id()
            val order = mutableListOf<String>()
            every { workspaceRepo.resolveConfirmation(any(), any()) } answers { order += "resolve" }
            coEvery { pageManager.handleWorkspaceSelection(any(), any()) } answers { order += "select" }

            val vm = vm()
            vm.executeScreenAction(
                WorkspaceScreenAction.HandleDialog(
                    ManagerDialogAction.CancelAndGoToWorkspace(
                        confirmationId = "c1",
                        workspaceId = editor,
                        sourceWorkspaceId = host,
                        hideManagerOverlay = false,
                    ),
                ),
            )
            advanceUntilIdle()

            // A still-pending confirmation would re-render in the destination pane the moment the
            // selection puts that tab on screen.
            order shouldBe listOf("resolve", "select")
            verify(exactly = 1) { workspaceRepo.resolveConfirmation("c1", false) }
            coVerify(exactly = 1) { pageManager.handleWorkspaceSelection(editor, host) }
        }

    @Test fun `the jump from the manager takes the overlay down once the tab is placed`() =
        runTest2(context = testDispatcher) {
            val editor = Workspace.Id()
            val order = mutableListOf<String>()
            coEvery { pageManager.handleWorkspaceSelection(any(), any()) } answers { order += "select" }
            every { pageManager.hideManagerOverlay() } answers { order += "hide" }

            val vm = vm()
            vm.executeScreenAction(
                WorkspaceScreenAction.HandleDialog(
                    ManagerDialogAction.CancelAndGoToWorkspace(
                        confirmationId = "c1",
                        workspaceId = editor,
                        sourceWorkspaceId = null,
                        hideManagerOverlay = true,
                    ),
                ),
            )
            advanceUntilIdle()

            // The overlay covers every pane, so a jump that leaves it up reveals nothing.
            order shouldBe listOf("select", "hide")
        }

    @Test fun `the jump from a pane leaves the overlay alone`() = runTest2(context = testDispatcher) {
        val editor = Workspace.Id()
        val host = Workspace.Id()

        val vm = vm()
        vm.executeScreenAction(
            WorkspaceScreenAction.HandleDialog(
                ManagerDialogAction.CancelAndGoToWorkspace(
                    confirmationId = "c1",
                    workspaceId = editor,
                    sourceWorkspaceId = host,
                    hideManagerOverlay = false,
                ),
            ),
        )
        advanceUntilIdle()

        // Nothing is covering the panes on this route, so hiding would dismiss an overlay the user
        // opened in the meantime.
        verify(exactly = 0) { pageManager.hideManagerOverlay() }
    }
}
