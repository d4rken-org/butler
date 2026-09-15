package eu.darken.butler.workspace.ui.workspaces

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onRoot
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.compose.tour.GuidedTourAccess
import eu.darken.butler.common.compose.tour.LocalGuidedTourController
import eu.darken.butler.common.compose.tour.LocalTourTargetRegistry
import eu.darken.butler.common.compose.tour.TourDefinition
import eu.darken.butler.common.compose.tour.TourId
import eu.darken.butler.common.compose.tour.TourSession
import eu.darken.butler.common.compose.tour.TourStep
import eu.darken.butler.common.compose.tour.TourTargetRegistry
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.WorkspaceRemote
import eu.darken.butler.workspace.core.layout.WorkspacePanelMode
import eu.darken.butler.workspace.ui.LocalWorkspacePageHosts
import eu.darken.butler.workspace.ui.WorkspacePageHostEntry
import eu.darken.butler.workspace.ui.manager.WorkspaceDesign
import eu.darken.butler.workspace.ui.workspaces.tour.FirstTabTour
import eu.darken.butler.workspace.ui.workspaces.tour.WorkspacePanesTour
import eu.darken.butler.workspace.ui.workspaces.tour.WorkspaceSwipeTour
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.junit.Test
import org.robolectric.annotation.Config
import testhelpers.ComposeTest

/**
 * The workspace tours' gating lives in [WorkspaceScreen], which is the only place that sees both
 * layouts. Every case here is a way a tour could fire on a screen that does not match it: not
 * actually "no tabs yet", nothing to swipe, no second pane, or a surface the user cannot act on.
 */
// Robolectric's default 320x470dp screen is too small for the classic empty state: the create card
// lands below the scroll viewport, where boundsInRoot() clips it away and no anchor is recorded -
// correct behaviour for an off-screen target, but not the situation under test.
@Config(qualifiers = "w720dp-h1600dp")
class WorkspaceScreenTourEligibilityTest : ComposeTest() {

    private val tabId = Workspace.Id()

    private val tabInfo = Workspace.Info(
        id = tabId,
        type = Workspace.Type.EXPLORER,
        title = "Explorer".toCaString(),
        lifecycleState = Workspace.LifecycleState.Ready,
    )

    private val secondTabInfo = Workspace.Info(
        id = Workspace.Id(),
        type = Workspace.Type.EXPLORER,
        title = "Downloads".toCaString(),
        lifecycleState = Workspace.LifecycleState.Ready,
    )

    private val fullScreenModalInfo = Workspace.Info(
        id = Workspace.Id(),
        type = Workspace.Type.EXPLORER,
        title = "Pick a folder".toCaString(),
        lifecycleState = Workspace.LifecycleState.Ready,
        callerWorkspaceId = tabId,
        modalPresentation = Workspace.ModalPresentationMode.FULL_SCREEN,
    )

    private val otherTourDefinition = TourDefinition(
        id = TourId("test.other.tour"),
        steps = listOf(TourStep(stepId = "only", targetId = null, body = "Other".toCaString())),
    )

    /**
     * Records what the screen asked to start, without the persistence a real controller has.
     *
     * [refuseWhileSessionLive] models the controller's mutex, which the default here does not: a
     * real `tryStart` returns false while another tour holds the session, and a screen whose start
     * latch is set from that return value has to survive losing the race.
     */
    private class RecordingTourAccess(
        private val refuseWhileSessionLive: Boolean = false,
    ) : GuidedTourAccess {
        private val _session = MutableStateFlow<TourSession?>(null)
        override val session: StateFlow<TourSession?> = _session
        val startedDefinitions = mutableListOf<TourDefinition>()
        val started: List<TourId> get() = startedDefinitions.map { it.id }
        var skipForNowCalls = 0
            private set

        /** Puts a foreign tour on screen, as another surface's start would. */
        fun holdSession(definition: TourDefinition) {
            _session.value = TourSession(definition, stepIndex = 0)
        }

        fun releaseSession() {
            _session.value = null
        }

        override suspend fun shouldStart(definition: TourDefinition): Boolean = true

        override suspend fun start(definition: TourDefinition) {
            tryStart(definition)
        }

        override suspend fun tryStart(definition: TourDefinition): Boolean {
            if (refuseWhileSessionLive && _session.value != null) return false
            startedDefinitions += definition
            _session.value = TourSession(definition, stepIndex = 0)
            return true
        }

        override suspend fun skipForNow() {
            skipForNowCalls++
            _session.value = null
        }
    }

    /** The real Explorer page instantiates Hilt ViewModels, so occupied panes get a stand-in. */
    private object BlankPageHost : WorkspacePageHostEntry {
        @Composable
        override fun Content(id: Workspace.Id, design: WorkspaceDesign) {
        }

        @Composable
        override fun Overlays(id: Workspace.Id, design: WorkspaceDesign) {
        }
    }

    private fun state(
        infos: List<Workspace.Info> = emptyList(),
        panelMode: WorkspacePanelMode = WorkspacePanelMode.SINGLE,
        isRestoring: Boolean = false,
        swipeGesturesEnabled: Boolean = false,
        onDemandWorkspaceCreation: Boolean = false,
    ) = WorkspacesViewModel.State(
        state = WorkspaceRemote.State(
            infos = infos,
            portraitPanelMode = panelMode,
            landscapePanelMode = panelMode,
        ),
        focusedWorkspace = infos.firstOrNull()?.id,
        selectedWorkspaces = infos.firstOrNull()?.let { mapOf(0 to it.id) } ?: emptyMap(),
        visiblePaneSelections = infos.firstOrNull()?.let { mapOf(0 to it.id) } ?: emptyMap(),
        isUpgraded = true,
        swipeGesturesEnabled = swipeGesturesEnabled,
        onDemandWorkspaceCreation = onDemandWorkspaceCreation,
        isRestoring = isRestoring,
    )

    /** A classic screen with something to swipe between. */
    private fun swipeState(
        infos: List<Workspace.Info> = listOf(tabInfo, secondTabInfo),
        onDemandWorkspaceCreation: Boolean = false,
    ) = state(
        infos = infos,
        panelMode = WorkspacePanelMode.SINGLE,
        swipeGesturesEnabled = true,
        onDemandWorkspaceCreation = onDemandWorkspaceCreation,
    )

    /** A multi-pane screen with an occupied pane, so the rail list has an anchor. */
    private fun panesState(infos: List<Workspace.Info> = listOf(tabInfo)) = state(
        infos = infos,
        panelMode = WorkspacePanelMode.DUAL_VERTICAL,
    )

    private class Harness(
        val tourAccess: RecordingTourAccess,
        val registry: TourTargetRegistry,
        val state: MutableState<WorkspacesViewModel.State>,
    )

    private fun setScreen(
        initialState: WorkspacesViewModel.State,
        isOverlayVisible: Boolean = false,
        tourAccess: RecordingTourAccess = RecordingTourAccess(),
    ): Harness {
        // The empty-state surfaces animate the mascot via an infinite Lottie loop, which floods
        // Robolectric's ShadowTrace per frame. Park the clock; nothing asserted here needs frames.
        composeTestRule.mainClock.autoAdvance = false
        val harness = Harness(
            tourAccess = tourAccess,
            registry = TourTargetRegistry(),
            state = mutableStateOf(initialState),
        )
        composeTestRule.setContent {
            PreviewWrapper {
                CompositionLocalProvider(
                    LocalGuidedTourController provides harness.tourAccess,
                    LocalTourTargetRegistry provides harness.registry,
                    LocalWorkspacePageHosts provides mapOf(Workspace.Type.EXPLORER to BlankPageHost),
                ) {
                    WorkspaceScreen(
                        state = harness.state.value,
                        managerDialogStates = emptyMap(),
                        isOverlayVisible = isOverlayVisible,
                        onScreenAction = {},
                    )
                }
            }
        }
        composeTestRule.waitForIdle()
        return harness
    }

    /**
     * Runs the recomposition and effects a mid-test state change triggers.
     *
     * The clock is parked (see [setScreen]), where `waitForIdle` publishes a write but never moves
     * the recomposer: without a frame the screen keeps composing the old state and the effects keyed
     * on it never restart. The trailing wait drains what those restarted effects dispatch.
     */
    private fun applyAndRecompose() {
        composeTestRule.waitForIdle()
        composeTestRule.mainClock.advanceTimeByFrame()
        composeTestRule.waitForIdle()
    }

    @Test
    fun `a settled tab-less screen starts the tour and tags the create card`() {
        val harness = setScreen(state())

        harness.tourAccess.started shouldBe listOf(FirstTabTour.id)
        harness.registry.has(FirstTabTour.CREATE_TAB_TARGET) shouldBe true
    }

    @Test
    fun `a restoring session does not start the tour even while the tab list is still empty`() {
        // Restoration begins with an empty tabWorkspaces, so emptiness alone would fire the tour on
        // every launch of a user who has saved tabs.
        val harness = setScreen(state(isRestoring = true))

        harness.tourAccess.started shouldBe emptyList()
        harness.registry.has(FirstTabTour.CREATE_TAB_TARGET) shouldBe false
    }

    @Test
    fun `an empty pane next to an occupied one is not a tab-less screen`() {
        // The occupied pane makes this a panes-tour screen, so what is asserted is that the
        // first-tab tour stayed out of it, not that nothing started at all.
        val harness = setScreen(state(infos = listOf(tabInfo), panelMode = WorkspacePanelMode.DUAL_VERTICAL))

        (FirstTabTour.id in harness.tourAccess.started) shouldBe false
        harness.registry.has(FirstTabTour.CREATE_TAB_TARGET) shouldBe false
    }

    @Test
    fun `the tour does not start while the tab manager overlay is up`() {
        val harness = setScreen(state(), isOverlayVisible = true)

        harness.tourAccess.started shouldBe emptyList()
    }

    @Test
    fun `only the first empty pane is tagged in a dual layout`() {
        val harness = setScreen(state(panelMode = WorkspacePanelMode.DUAL_VERTICAL))

        harness.tourAccess.started shouldBe listOf(FirstTabTour.id)
        val registered = harness.registry.get(FirstTabTour.CREATE_TAB_TARGET)
        (registered != null) shouldBe true
        // Both panes are empty here. If the second one were tagged too its registration would land
        // last and win, so the anchor sitting in the leading half is what proves it did not.
        val rootBounds = composeTestRule.onRoot().getUnclippedBoundsInRoot()
        val rootWidth = rootBounds.right - rootBounds.left
        val anchorCenterX = with(composeTestRule.density) { registered!!.center.x.toDp() }
        (anchorCenterX < rootWidth / 2) shouldBe true
    }

    // Robolectric's default 320x470dp screen, i.e. the case the class-level qualifier hides: a
    // compact window, split screen, large font scale or a short adaptive pane all put the create
    // card below the scroll viewport.
    @Test
    @Config(qualifiers = "w320dp-h470dp")
    fun `on a short viewport the create card anchors only after prepareTarget has run`() {
        val harness = setScreen(state())

        // Card is composed but clipped away by the scroll viewport, so nothing registers and the
        // one-step tour would grace-skip.
        harness.registry.has(FirstTabTour.CREATE_TAB_TARGET) shouldBe false

        val prepare = harness.tourAccess.startedDefinitions.single().steps.single().prepareTarget!!
        val prepareJob = CoroutineScope(Dispatchers.Main).launch { prepare() }
        // The clock is parked (see setScreen), so the bring-into-view scroll animation is stepped
        // frame by frame - a single large advance does not run it to completion.
        repeat(20) {
            composeTestRule.mainClock.advanceTimeByFrame()
            composeTestRule.waitForIdle()
        }

        harness.registry.has(FirstTabTour.CREATE_TAB_TARGET) shouldBe true
        prepareJob.cancel()
    }

    /** The single-with-rail layout renders through the adaptive route, which tags its own panes. */
    @Test
    fun `the create-tab anchor exists in the single-with-rail layout`() {
        val harness = setScreen(state(panelMode = WorkspacePanelMode.SINGLE_RAIL))

        harness.tourAccess.started shouldBe listOf(FirstTabTour.id)
        harness.registry.has(FirstTabTour.CREATE_TAB_TARGET) shouldBe true
    }

    @Test
    fun `the create-tab anchor survives a single to dual layout change`() {
        // A layout-specific target id would strand a running tour on rotation or a panel-mode
        // change: the old anchor unregisters, the host grace-skips and persists the completion.
        val harness = setScreen(state(panelMode = WorkspacePanelMode.SINGLE))
        harness.registry.has(FirstTabTour.CREATE_TAB_TARGET) shouldBe true

        composeTestRule.runOnIdle {
            harness.state.value = state(panelMode = WorkspacePanelMode.DUAL_VERTICAL)
        }
        composeTestRule.waitForIdle()

        harness.registry.has(FirstTabTour.CREATE_TAB_TARGET) shouldBe true
        // Still a single start: the tour was already published, not restarted by the layout change.
        harness.tourAccess.started shouldBe listOf(FirstTabTour.id)
    }

    @Test
    fun `the swipe tour starts on a classic screen with two tabs`() {
        val harness = setScreen(swipeState())

        harness.tourAccess.started shouldBe listOf(WorkspaceSwipeTour.id)
    }

    @Test
    fun `one tab is nothing to swipe between`() {
        val harness = setScreen(swipeState(infos = listOf(tabInfo)))

        harness.tourAccess.started shouldBe emptyList()
    }

    @Test
    fun `a restoring session does not start the swipe tour`() {
        val harness = setScreen(
            state(
                infos = listOf(tabInfo, secondTabInfo),
                swipeGesturesEnabled = true,
                isRestoring = true,
            ),
        )

        harness.tourAccess.started shouldBe emptyList()
    }

    @Test
    fun `the swipe tour stays away when swipe gestures are off`() {
        val harness = setScreen(state(infos = listOf(tabInfo, secondTabInfo)))

        harness.tourAccess.started shouldBe emptyList()
    }

    @Test
    fun `the swipe tour stays away from a multi-pane layout`() {
        // The adaptive layout composes no pager, so there is nothing to swipe there.
        val harness = setScreen(
            state(
                infos = listOf(tabInfo, secondTabInfo),
                panelMode = WorkspacePanelMode.DUAL_VERTICAL,
                swipeGesturesEnabled = true,
            ),
        )

        (WorkspaceSwipeTour.id in harness.tourAccess.started) shouldBe false
    }

    @Test
    fun `a full-screen modal keeps the swipe tour off the screen`() {
        val harness = setScreen(swipeState(infos = listOf(tabInfo, secondTabInfo, fullScreenModalInfo)))

        harness.tourAccess.started shouldBe emptyList()
    }

    @Test
    fun `swiping past the last tab is taught while it creates a tab`() {
        val harness = setScreen(swipeState(onDemandWorkspaceCreation = true))

        harness.tourAccess.startedDefinitions.single().steps.map { it.stepId } shouldBe
            listOf("switch", "createBySwipe", "panes")
    }

    @Test
    fun `the create-by-swipe step is dropped when the pager offers no placeholder page`() {
        val harness = setScreen(swipeState(onDemandWorkspaceCreation = false))

        harness.tourAccess.startedDefinitions.single().steps.map { it.stepId } shouldBe
            listOf("switch", "panes")
    }

    @Test
    fun `the panes tour starts in a dual layout with a tab`() {
        val harness = setScreen(panesState())

        harness.tourAccess.started shouldBe listOf(WorkspacePanesTour.id)
        harness.registry.has(WorkspacePanesTour.RAIL_LIST_TARGET) shouldBe true
        harness.registry.has(WorkspacePanesTour.PANE_DIVIDER_TARGET) shouldBe true
    }

    @Test
    fun `the panes tour stays away from the classic layout`() {
        val harness = setScreen(state(infos = listOf(tabInfo)))

        (WorkspacePanesTour.id in harness.tourAccess.started) shouldBe false
    }

    @Test
    fun `the single-with-rail layout has a rail but no divider to point at`() {
        val harness = setScreen(state(infos = listOf(tabInfo), panelMode = WorkspacePanelMode.SINGLE_RAIL))

        (WorkspacePanesTour.id in harness.tourAccess.started) shouldBe false
    }

    @Test
    fun `without a tab the rail list has no anchor for the panes tour`() {
        val harness = setScreen(panesState(infos = emptyList()))

        (WorkspacePanesTour.id in harness.tourAccess.started) shouldBe false
    }

    @Test
    fun `a full-screen modal keeps the panes tour off the screen`() {
        val harness = setScreen(panesState(infos = listOf(tabInfo, fullScreenModalInfo)))

        (WorkspacePanesTour.id in harness.tourAccess.started) shouldBe false
    }

    @Test
    fun `a swipe tour session ends when the window grows a rail`() {
        val harness = setScreen(swipeState())
        harness.tourAccess.started shouldBe listOf(WorkspaceSwipeTour.id)

        composeTestRule.runOnIdle {
            harness.state.value = state(
                infos = listOf(tabInfo, secondTabInfo),
                panelMode = WorkspacePanelMode.DUAL_VERTICAL,
                swipeGesturesEnabled = true,
            )
        }
        applyAndRecompose()

        harness.tourAccess.skipForNowCalls shouldBe 1
    }

    @Test
    fun `a panes tour session ends when the window goes back to one pane`() {
        val harness = setScreen(panesState())
        harness.tourAccess.started shouldBe listOf(WorkspacePanesTour.id)

        composeTestRule.runOnIdle {
            harness.state.value = state(infos = listOf(tabInfo), panelMode = WorkspacePanelMode.SINGLE)
        }
        applyAndRecompose()

        harness.tourAccess.skipForNowCalls shouldBe 1
    }

    @Test
    fun `a start that lost the controller to another tour fires once that session ends`() {
        val tourAccess = RecordingTourAccess(refuseWhileSessionLive = true)
        tourAccess.holdSession(otherTourDefinition)
        val harness = setScreen(swipeState(), tourAccess = tourAccess)

        harness.tourAccess.started shouldBe emptyList()

        composeTestRule.runOnIdle { harness.tourAccess.releaseSession() }
        applyAndRecompose()

        harness.tourAccess.started shouldBe listOf(WorkspaceSwipeTour.id)
    }
}
