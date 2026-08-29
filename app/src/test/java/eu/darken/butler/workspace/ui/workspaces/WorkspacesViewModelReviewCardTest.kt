package eu.darken.butler.workspace.ui.workspaces

import android.app.Activity
import androidx.lifecycle.SavedStateHandle
import eu.darken.butler.common.WebpageTool
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.compose.tour.GuidedTourController
import eu.darken.butler.common.compose.tour.TourDefinition
import eu.darken.butler.common.compose.tour.TourId
import eu.darken.butler.common.compose.tour.TourSession
import eu.darken.butler.common.compose.tour.TourStep
import eu.darken.butler.common.datastore.DataStoreValue
import eu.darken.butler.common.debug.bugreport.BugReportRepo
import eu.darken.butler.common.error.ErrorIncidentFactory
import eu.darken.butler.common.error.ErrorReportPackager
import eu.darken.butler.common.error.ErrorReportTool
import eu.darken.butler.common.review.ReviewTool
import eu.darken.butler.main.core.motd.MotdApi
import eu.darken.butler.main.core.motd.MotdRepo
import eu.darken.butler.main.core.motd.MotdState
import eu.darken.butler.upgrade.UpgradeRepo
import eu.darken.butler.workspace.core.OpenInNewTabsUseCase
import eu.darken.butler.workspace.core.PendingWorkspaceConfirmation
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.WorkspaceRemote
import eu.darken.butler.workspace.core.WorkspaceRepo
import eu.darken.butler.workspace.core.WorkspaceSettings
import eu.darken.butler.workspace.core.undo.ClosedWorkspaceStash
import eu.darken.butler.workspace.ui.WorkspacePageManager
import eu.darken.butler.workspace.ui.WorkspaceVisibilityTracker
import eu.darken.butler.workspace.ui.floatingbar.WorkspaceBarCollapseStates
import eu.darken.butler.workspace.ui.scroll.WorkspaceScrollPositions
import eu.darken.butler.workspace.ui.session.WorkspaceSessionManager
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
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
import java.util.Locale
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

/**
 * The review card is the lowest-priority surface on the workspace screen: anything that asks the
 * user for a decision, or covers the screen, has to win over asking them for a favor.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApplication::class)
class WorkspacesViewModelReviewCardTest : BaseTest() {

    private val testDispatcher = StandardTestDispatcher()
    private val reviewTool = mockk<ReviewTool>(relaxed = true)

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

    private fun workspaceInfo(
        id: Workspace.Id,
        caller: Workspace.Id? = null,
        modalPresentation: Workspace.ModalPresentationMode = Workspace.ModalPresentationMode.FULL_SCREEN,
    ) = Workspace.Info(
        id = id,
        type = Workspace.Type.EXPLORER,
        title = "Workspace ${id.shortTag}".toCaString(),
        callerWorkspaceId = caller,
        modalPresentation = modalPresentation,
    )

    private fun motd() = MotdState(
        motd = MotdApi.Motd(
            id = Uuid.random(),
            message = "Something the user has to read first",
            primaryLink = null,
            minimumVersion = null,
            maximumVersion = null,
        ),
        locale = Locale.ENGLISH,
    )

    private fun tourSession() = TourSession(
        definition = TourDefinition(
            id = TourId("test.tour"),
            steps = listOf(TourStep(stepId = "step", body = "Let me show you around".toCaString())),
        ),
        stepIndex = 0,
    )

    private fun TestScope.vm(
        infos: List<Workspace.Info> = emptyList(),
        paneCount: Int = 1,
        isManagerOverlayVisible: Boolean = false,
        motd: MotdState? = null,
        confirmations: Map<String, PendingWorkspaceConfirmation> = emptyMap(),
        tourSession: TourSession? = null,
        reviewState: kotlinx.coroutines.flow.Flow<ReviewTool.State> = flowOf(
            ReviewTool.State(shouldAskForReview = true),
        ),
    ): WorkspacesViewModel {
        every { reviewTool.state } returns reviewState

        val workspaceRepo = mockk<WorkspaceRepo>(relaxed = true).apply {
            every { state } returns flowOf(WorkspaceRemote.State(infos = infos))
            every { events } returns emptyFlow()
            every { pendingConfirmations } returns flowOf(confirmations)
        }
        val workspaceSettings = mockk<WorkspaceSettings>(relaxed = true).apply {
            every { swipeGesturesEnabled } returns boolSetting(true)
            every { onDemandWorkspaceCreation } returns boolSetting(true)
            every { paneClickToFocus } returns boolSetting(true)
        }
        val pageManager = mockk<WorkspacePageManager>(relaxed = true).apply {
            every { state } returns MutableStateFlow(
                WorkspacePageManager.State(
                    currentPaneCount = paneCount,
                    isManagerOverlayVisible = isManagerOverlayVisible,
                ),
            )
        }
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
            every { this@apply.motd } returns flowOf(motd)
        }
        val bugReportRepo = mockk<BugReportRepo>(relaxed = true).apply {
            every { hasUnseenCrashes } returns flowOf(false)
        }
        val guidedTourController = mockk<GuidedTourController>(relaxed = true).apply {
            every { session } returns MutableStateFlow(tourSession)
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
            errorIncidentFactory = mockk<ErrorIncidentFactory>(relaxed = true),
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

    private suspend fun WorkspacesViewModel.settledState() =
        withTimeoutOrNull(10.seconds) { state.filterNotNull().first() }

    @Test fun `a quiet screen shows the review card`() = runTest2(context = testDispatcher) {
        val vm = vm()
        advanceUntilIdle()

        // Positive control for the suppression cases below: without it they could pass for the
        // wrong reason (e.g. the card never being wired up at all).
        vm.settledState()!!.showReviewCard shouldBe true
    }

    @Test fun `a MOTD suppresses the review card`() = runTest2(context = testDispatcher) {
        val vm = vm(motd = motd())
        advanceUntilIdle()

        vm.settledState()!!.showReviewCard shouldBe false
    }

    @Test fun `the manager overlay suppresses the review card`() = runTest2(context = testDispatcher) {
        val vm = vm(isManagerOverlayVisible = true)
        advanceUntilIdle()

        // The card lives in the workspace screen's overlay slot, which the manager covers.
        vm.settledState()!!.showReviewCard shouldBe false
    }

    @Test fun `a manager dialog suppresses the review card`() = runTest2(context = testDispatcher) {
        val vm = vm(
            confirmations = mapOf(
                "limit" to PendingWorkspaceConfirmation(
                    id = "limit",
                    sourceWorkspaceId = null,
                    data = PendingWorkspaceConfirmation.ConfirmationData.WorkspaceLimitReached(
                        currentCount = 5,
                        limit = 5,
                    ),
                ),
            ),
        )
        advanceUntilIdle()

        // A pending confirmation is a decision the user owes, it outranks a favor.
        vm.settledState()!!.showReviewCard shouldBe false
    }

    @Test fun `a pane-local modal chain suppresses the review card`() = runTest2(context = testDispatcher) {
        val explorer = Workspace.Id()
        val details = Workspace.Id()
        val vm = vm(
            infos = listOf(
                workspaceInfo(explorer),
                workspaceInfo(
                    id = details,
                    caller = explorer,
                    modalPresentation = Workspace.ModalPresentationMode.PANE_LOCAL,
                ),
            ),
            paneCount = 2,
        )
        advanceUntilIdle()

        vm.settledState()!!.showReviewCard shouldBe false
    }

    @Test fun `a full-screen modal suppresses the review card`() = runTest2(context = testDispatcher) {
        val explorer = Workspace.Id()
        val picker = Workspace.Id()
        val vm = vm(
            infos = listOf(
                workspaceInfo(explorer),
                workspaceInfo(
                    id = picker,
                    caller = explorer,
                    modalPresentation = Workspace.ModalPresentationMode.FULL_SCREEN,
                ),
            ),
            paneCount = 1,
        )
        advanceUntilIdle()

        // The other bucket: this chain lands in fullScreenModalWorkspace, not in the pane-local map,
        // so checking only one of the two would let the card render under a covering modal.
        vm.settledState()!!.showReviewCard shouldBe false
    }

    @Test fun `a pane-local modal chain suppresses the review card in single pane too`() =
        runTest2(context = testDispatcher) {
            val explorer = Workspace.Id()
            val details = Workspace.Id()
            val vm = vm(
                infos = listOf(
                    workspaceInfo(explorer),
                    workspaceInfo(
                        id = details,
                        caller = explorer,
                        modalPresentation = Workspace.ModalPresentationMode.PANE_LOCAL,
                    ),
                ),
                paneCount = 1,
            )
            advanceUntilIdle()

            // It stacks inside its tab's page rather than covering the screen, but it still owns
            // everything the user is looking at.
            vm.settledState()!!.showReviewCard shouldBe false
        }

    @Test fun `an active guided tour suppresses the review card`() = runTest2(context = testDispatcher) {
        val vm = vm(tourSession = tourSession())
        advanceUntilIdle()

        // A tour scrims the whole screen, so the card would sit underneath it dimmed and untappable.
        vm.settledState()!!.showReviewCard shouldBe false
    }

    @Test fun `no guided tour session shows the review card`() = runTest2(context = testDispatcher) {
        val vm = vm(tourSession = null)
        advanceUntilIdle()

        vm.settledState()!!.showReviewCard shouldBe true
    }

    @Test fun `dismissing the card delegates to the review tool`() = runTest2(context = testDispatcher) {
        val vm = vm()

        vm.reviewDismiss()
        advanceUntilIdle()

        coVerify(exactly = 1) { reviewTool.dismiss() }
    }

    @Test fun `reviewing forwards the very activity it was handed`() = runTest2(context = testDispatcher) {
        val vm = vm()
        val activity = mockk<Activity>(relaxed = true)

        vm.reviewNow(activity)
        advanceUntilIdle()

        // Play's flow launches against a concrete activity, a substituted one would break it.
        coVerify(exactly = 1) { reviewTool.reviewNow(activity) }
    }

    @Test fun `a failing review pipeline leaves the workspace state usable`() = runTest2(context = testDispatcher) {
        val explorer = Workspace.Id()
        val vm = vm(
            infos = listOf(workspaceInfo(explorer)),
            reviewState = flow {
                emit(ReviewTool.State(shouldAskForReview = true))
                throw IllegalStateException("review pipeline died")
            },
        )
        advanceUntilIdle()

        // Without the ViewModel's catch the failure would take the whole state flow down and the
        // screen would never render again.
        val state = vm.settledState()
        state.shouldNotBeNull()
        state.tabWorkspaces.map { it.id } shouldBe listOf(explorer)
        state.showReviewCard shouldBe false
    }
}
