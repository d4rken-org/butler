package eu.darken.butler.workspace.ui.workspaces

import androidx.lifecycle.SavedStateHandle
import eu.darken.butler.common.WebpageTool
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.compose.tour.GuidedTourController
import eu.darken.butler.common.datastore.DataStoreValue
import eu.darken.butler.common.debug.bugreport.BugReportRepo
import eu.darken.butler.common.error.ErrorReportPackager
import eu.darken.butler.common.error.ErrorReportTool
import eu.darken.butler.common.error.LocalizedErrorContext
import eu.darken.butler.common.review.ReviewTool
import eu.darken.butler.main.core.motd.MotdRepo
import eu.darken.butler.upgrade.UpgradeRepo
import eu.darken.butler.workspace.core.OpenInNewTabsUseCase
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.WorkspaceRemote
import eu.darken.butler.workspace.core.WorkspaceRepo
import eu.darken.butler.workspace.core.WorkspaceSettings
import eu.darken.butler.workspace.core.session.SessionRestorationException
import eu.darken.butler.workspace.core.undo.ClosedWorkspaceStash
import eu.darken.butler.workspace.ui.WorkspacePageManager
import eu.darken.butler.workspace.ui.WorkspaceVisibilityTracker
import eu.darken.butler.workspace.ui.floatingbar.WorkspaceBarCollapseStates
import eu.darken.butler.workspace.ui.scroll.WorkspaceScrollPositions
import eu.darken.butler.workspace.ui.session.WorkspaceSessionManager
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
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
import testhelpers.error.recordingIncidentStore

/**
 * Both failures this screen can share are frozen where they are published, so the report carries
 * the log trail from around the failure instead of from whenever Share was tapped.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApplication::class)
class WorkspacesViewModelErrorIncidentTest : BaseTest() {

    private val testDispatcher = StandardTestDispatcher()
    private val workspaceRepo = mockk<WorkspaceRepo>(relaxed = true)
    private val pageManager = mockk<WorkspacePageManager>(relaxed = true)
    private val incidentStore = recordingIncidentStore()
    private val sessionState = MutableStateFlow<WorkspaceSessionManager.State>(
        WorkspaceSessionManager.State.Disabled,
    )

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

    private fun TestScope.vm(infos: List<Workspace.Info> = emptyList()): WorkspacesViewModel {
        every { workspaceRepo.state } returns MutableStateFlow(WorkspaceRemote.State(infos = infos))
        every { workspaceRepo.events } returns emptyFlow()
        every { workspaceRepo.pendingConfirmations } returns flowOf(emptyMap())

        val workspaceSettings = mockk<WorkspaceSettings>(relaxed = true).apply {
            every { swipeGesturesEnabled } returns boolSetting(true)
            every { onDemandWorkspaceCreation } returns boolSetting(true)
            every { paneClickToFocus } returns boolSetting(true)
        }
        every { pageManager.state } returns MutableStateFlow(WorkspacePageManager.State())
        val sessionManager = mockk<WorkspaceSessionManager>(relaxed = true).apply {
            every { state } returns sessionState
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
            errorReportPackager = mockk<ErrorReportPackager>(relaxed = true),
            errorIncidentStore = incidentStore,
            bugReportRepo = bugReportRepo,
            openInNewTabsUseCase = mockk<OpenInNewTabsUseCase>(relaxed = true),
            reviewTool = reviewTool,
            guidedTourController = guidedTourController,
            closedStash = ClosedWorkspaceStash(backgroundScope),
            pageHosts = emptyMap(),
            scrollPositions = mockk<WorkspaceScrollPositions>(relaxed = true),
            barCollapseStates = mockk<WorkspaceBarCollapseStates>(relaxed = true),
            pagerVisibility = WorkspaceVisibilityTracker(),
        )
    }

    @Test fun `sharing a workspace that failed to start uses the incident from that failure`() =
        runTest2(context = testDispatcher) {
            val sentinel = IllegalStateException("init failed")
            val workspaceId = Workspace.Id()
            val vm = vm(
                infos = listOf(
                    Workspace.Info(
                        id = workspaceId,
                        type = Workspace.Type.EXPLORER,
                        title = "tab".toCaString(),
                        lifecycleState = Workspace.LifecycleState.Error(sentinel),
                    ),
                ),
            )
            advanceUntilIdle()

            vm.shareWorkspaceError(workspaceId, sentinel)
            advanceUntilIdle()

            val incident = vm.pendingErrorShare.value!!.incident
            (incident.error === sentinel) shouldBe true
            incident.occurredAtIsApproximate shouldBe false
            incident.context["workspace.type"] shouldBe "EXPLORER"
            incident.context.containsKey("incident.frozenAtShare") shouldBe false
        }

    /**
     * The dialog holds a [SessionRestorationException] wrapping the real failure, so the share has
     * to carry the incident the restoration failure was frozen into, not look one up by the wrapper.
     */
    @Test fun `sharing a failed session restore uses the incident from that failure`() =
        runTest2(context = testDispatcher) {
            val sentinel = IllegalStateException("restore failed")
            sessionState.value = WorkspaceSessionManager.State.Error(sentinel)
            val vm = vm()
            advanceUntilIdle()

            val raised = vm.errorEvents.first().shouldBeInstanceOf<SessionRestorationException>()
            raised.getLocalizedError(LocalizedErrorContext()).infoAction!!.invoke()
            advanceUntilIdle()

            val incident = vm.pendingErrorShare.value!!.incident
            (incident.error === sentinel) shouldBe true
            incident.occurredAtIsApproximate shouldBe false
            incident.context.containsKey("session.phase") shouldBe true
            incident.context.containsKey("incident.frozenAtShare") shouldBe false
        }
}
