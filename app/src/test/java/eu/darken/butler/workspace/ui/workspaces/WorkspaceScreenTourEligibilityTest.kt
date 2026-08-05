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
import eu.darken.butler.common.compose.tour.TourTargetRegistry
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.WorkspaceRemote
import eu.darken.butler.workspace.core.layout.WorkspacePanelMode
import eu.darken.butler.workspace.ui.LocalWorkspacePageHosts
import eu.darken.butler.workspace.ui.WorkspacePageHostEntry
import eu.darken.butler.workspace.ui.manager.WorkspaceDesign
import eu.darken.butler.workspace.ui.workspaces.tour.FirstTabTour
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
 * The first-tab tour's gating lives in [WorkspaceScreen], which is the only place that sees both
 * layouts. Every case here is a way the tour could fire on a screen that is not actually "no tabs
 * yet", or anchor on a card the user cannot act on.
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

    /** Records what the screen asked to start, without the persistence a real controller has. */
    private class RecordingTourAccess : GuidedTourAccess {
        private val _session = MutableStateFlow<TourSession?>(null)
        override val session: StateFlow<TourSession?> = _session
        val startedDefinitions = mutableListOf<TourDefinition>()
        val started: List<TourId> get() = startedDefinitions.map { it.id }

        override suspend fun shouldStart(definition: TourDefinition): Boolean = true

        override suspend fun start(definition: TourDefinition) {
            tryStart(definition)
        }

        override suspend fun tryStart(definition: TourDefinition): Boolean {
            startedDefinitions += definition
            _session.value = TourSession(definition, stepIndex = 0)
            return true
        }

        override suspend fun skipForNow() {}
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
        swipeGesturesEnabled = false,
        onDemandWorkspaceCreation = false,
        isRestoring = isRestoring,
    )

    private class Harness(
        val tourAccess: RecordingTourAccess,
        val registry: TourTargetRegistry,
        val state: MutableState<WorkspacesViewModel.State>,
    )

    private fun setScreen(
        initialState: WorkspacesViewModel.State,
        isOverlayVisible: Boolean = false,
    ): Harness {
        // The empty-state surfaces animate the mascot via an infinite Lottie loop, which floods
        // Robolectric's ShadowTrace per frame. Park the clock; nothing asserted here needs frames.
        composeTestRule.mainClock.autoAdvance = false
        val harness = Harness(
            tourAccess = RecordingTourAccess(),
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
        val harness = setScreen(state(infos = listOf(tabInfo), panelMode = WorkspacePanelMode.DUAL_VERTICAL))

        harness.tourAccess.started shouldBe emptyList()
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
}
