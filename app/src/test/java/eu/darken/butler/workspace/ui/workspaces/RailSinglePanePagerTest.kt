package eu.darken.butler.workspace.ui.workspaces

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import eu.darken.butler.R
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.WorkspaceAction
import eu.darken.butler.workspace.core.WorkspaceRemote
import eu.darken.butler.workspace.core.layout.WorkspacePanelMode
import eu.darken.butler.workspace.ui.LocalWorkspacePageHosts
import eu.darken.butler.workspace.ui.LocalWorkspacePagerVisibility
import eu.darken.butler.workspace.ui.WorkspacePageHostEntry
import eu.darken.butler.workspace.ui.WorkspaceVisibilityTracker
import eu.darken.butler.workspace.ui.dialogs.ManagerDialog
import eu.darken.butler.workspace.ui.dialogs.ManagerDialogAction
import eu.darken.butler.workspace.ui.manager.LocalWorkspaceButtonProvider
import eu.darken.butler.workspace.ui.manager.WorkspaceButtonProvider
import eu.darken.butler.workspace.ui.manager.WorkspaceButtonViewModel
import eu.darken.butler.workspace.ui.manager.WorkspaceDesign
import eu.darken.butler.workspace.ui.template.QuickCreateItem
import eu.darken.butler.workspace.ui.workspaces.adaptive.WorkspaceNavigationRailDefaults
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.junit.Test
import org.robolectric.annotation.Config
import testhelpers.ComposeTest

/**
 * The one-pane rail layouts render their pane through the tab pager, so a tab can be swiped to
 * with the rail beside it.
 *
 * The whole screen is composed, since what is under test is the wiring between the rail and the
 * pager. The harness applies `Select` to focus and the pane assignment, the way the ViewModel does,
 * and only records the rest - which is what keeps focus on the last tab while the pager rests on
 * the new-tab page, since the tab that page asks for never arrives.
 */
@Config(qualifiers = "w720dp-h1280dp-port")
class RailSinglePanePagerTest : ComposeTest() {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    private val firstTab = Workspace.Info(
        id = Workspace.Id(),
        type = Workspace.Type.EXPLORER,
        title = "Explorer".toCaString(),
        lifecycleState = Workspace.LifecycleState.Ready,
    )

    private val secondTab = Workspace.Info(
        id = Workspace.Id(),
        type = Workspace.Type.EXPLORER,
        title = "Downloads".toCaString(),
        lifecycleState = Workspace.LifecycleState.Ready,
    )

    private val actions = mutableListOf<WorkspaceScreenAction>()

    private val visibility = WorkspaceVisibilityTracker()

    private var panelMode by mutableStateOf(WorkspacePanelMode.SINGLE_RAIL)

    private var focused by mutableStateOf<Workspace.Id?>(null)

    /** Window-level dialogs; the screen hands them on but only its host renders them. */
    private val globalDialogs = mutableStateListOf<ManagerDialog>()

    /** Every page the pager composed, in order. */
    private val composedPages = mutableListOf<Workspace.Id>()

    /** Pane-hosted close confirmations, keyed by the tab whose page hosts them; filled in by [buttonProvider]. */
    private val dialogStates = mutableStateMapOf<Workspace.Id, ManagerDialog.WorkspaceTargeted>()

    /**
     * Answers a rail Close that needs confirmation the way `WorkspacesViewModel` routes it: in the
     * pane only when the close takes the pane's own tab down, as a window dialog otherwise.
     */
    private val buttonProvider = object : WorkspaceButtonProvider {
        override val state: Flow<WorkspaceButtonViewModel.State?> = flowOf(null)
        override fun executeWorkspaceAction(action: WorkspaceAction) {
            if (action !is WorkspaceAction.Close) return
            val id = "close-${action.id.shortTag}"
            if (action.sourceWorkspaceId == null) {
                dialogStates[action.id] = ManagerDialog.WorkspaceTargeted.CloseConfirmation(
                    id = id,
                    targetWorkspaceId = action.id,
                    closingWorkspaceId = action.id,
                    workspaceTitle = "notes.txt".toCaString(),
                    hasUnsavedChanges = true,
                )
            } else {
                globalDialogs += ManagerDialog.Global.CloseConfirmation(
                    id = id,
                    closingWorkspaceId = action.id,
                    workspaceTitle = "notes.txt".toCaString(),
                    hasUnsavedChanges = true,
                    selectionSourceWorkspaceId = action.sourceWorkspaceId,
                    canGoToWorkspace = true,
                )
            }
        }

        override fun navToWorkspaceManager() = Unit
        override fun navToSettings() = Unit
        override fun navToUpgradeButler() = Unit
        override fun createWorkspace(item: QuickCreateItem) = Unit
        override fun createTemplatesWorkspace() = Unit
        override fun setPanelMode(landscape: Boolean, mode: WorkspacePanelMode, recommendedPaneCount: Int) = Unit
    }

    private inner class TaggingHost : WorkspacePageHostEntry {
        @Composable
        override fun Content(id: Workspace.Id, design: WorkspaceDesign) {
            SideEffect { composedPages += id }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .testTag(pageTag(id)),
            )
        }

        @Composable
        override fun Overlays(id: Workspace.Id, design: WorkspaceDesign) = Unit
    }

    private fun setScreen(
        tabs: List<Workspace.Info> = listOf(firstTab, secondTab),
        focused: Workspace.Id? = tabs.firstOrNull()?.id,
        swipe: Boolean = true,
        onDemand: Boolean = false,
        settleAfter: Boolean = true,
    ) {
        this.focused = focused
        val host = TaggingHost()
        fun state() = WorkspacesViewModel.State(
            state = WorkspaceRemote.State(
                infos = tabs,
                portraitPanelMode = panelMode,
                landscapePanelMode = panelMode,
            ),
            focusedWorkspace = this.focused,
            selectedWorkspaces = this.focused?.let { mapOf(0 to it) } ?: emptyMap(),
            visiblePaneSelections = this.focused?.let { mapOf(0 to it) } ?: emptyMap(),
            isUpgraded = true,
            swipeGesturesEnabled = swipe,
            onDemandWorkspaceCreation = onDemand,
            currentPaneCount = 1,
        )
        // The rail's mascot animates on an endless loop, which never lets the clock idle.
        composeTestRule.mainClock.autoAdvance = false
        composeTestRule.setContent {
            PreviewWrapper {
                CompositionLocalProvider(
                    LocalWorkspacePageHosts provides mapOf(Workspace.Type.EXPLORER to host),
                    LocalWorkspaceButtonProvider provides buttonProvider,
                    LocalWorkspacePagerVisibility provides visibility,
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .testTag(ROOT_TAG),
                    ) {
                        WorkspaceScreen(
                            state = state(),
                            managerDialogStates = dialogStates,
                            managerDialogs = globalDialogs,
                            onScreenAction = { action ->
                                actions.add(action)
                                if (action is WorkspaceScreenAction.Select) this@RailSinglePanePagerTest.focused = action.id
                            },
                        )
                    }
                }
            }
        }
        if (settleAfter) settle()
    }

    /** Runs the parked clock long enough for a fling, a snap or a programmatic page scroll to finish. */
    private fun settle() {
        repeat(20) {
            composeTestRule.mainClock.advanceTimeBy(100)
            composeTestRule.waitForIdle()
        }
    }

    private fun swipeLeftOn(id: Workspace.Id) {
        composeTestRule.onNodeWithTag(pageTag(id)).performTouchInput { swipeLeft() }
        settle()
    }

    private fun openRailMenu(index: Int) {
        composeTestRule.onAllNodesWithTag(WorkspaceNavigationRailDefaults.ITEM_TEST_TAG)[index].performClick()
        // The clock is parked, so a state change needs a frame before it has recomposed.
        composeTestRule.mainClock.advanceTimeByFrame()
        composeTestRule.waitForIdle()
    }

    private fun pickFromMenu(label: Int) {
        composeTestRule.onNodeWithText(context.getString(label)).performClick()
        settle()
    }

    /**
     * Resting on the new-tab page asks for a tab straight away, and the placeholder switches to its
     * creating state. The harness never applies that request, so the pager stays parked there.
     */
    private fun swipeOntoPlaceholder() {
        swipeLeftOn(secondTab.id)
        actions shouldContain WorkspaceScreenAction.CreateOnDemand
        composeTestRule.onNodeWithText(context.getString(R.string.workspace_creating_placeholder), useUnmergedTree = true)
            .assertIsDisplayed()
        composeTestRule.onNodeWithTag(pageTag(secondTab.id)).assertIsNotDisplayed()
    }

    @Test
    fun `a swipe on the sole pane turns to the next tab`() {
        setScreen()

        swipeLeftOn(firstTab.id)

        composeTestRule.onNodeWithTag(pageTag(secondTab.id)).assertIsDisplayed()
        actions shouldContain WorkspaceScreenAction.Select(secondTab.id)
    }

    @Test
    fun `with swipe gestures off the sole pane stays put`() {
        setScreen(swipe = false)

        swipeLeftOn(firstTab.id)

        composeTestRule.onNodeWithTag(pageTag(firstTab.id)).assertIsDisplayed()
        actions shouldNotContain WorkspaceScreenAction.Select(secondTab.id)
    }

    @Test
    fun `the bottom rail keeps its strip below the pager`() {
        setScreen()

        val root = composeTestRule.onNodeWithTag(ROOT_TAG).getUnclippedBoundsInRoot()
        val rail = composeTestRule.onNodeWithTag(WorkspaceNavigationRailDefaults.SURFACE_TEST_TAG)
            .getUnclippedBoundsInRoot()
        val page = composeTestRule.onNodeWithTag(pageTag(firstTab.id)).getUnclippedBoundsInRoot()

        rail.bottom shouldBe root.bottom
        (rail.bottom - rail.top > 0.dp) shouldBe true
        (page.bottom <= rail.top) shouldBe true
        (page.bottom > page.top) shouldBe true
        composeTestRule.onAllNodesWithTag(WorkspaceNavigationRailDefaults.ITEM_TEST_TAG)[0].assertIsDisplayed()
    }

    @Test
    fun `the bottom rail keeps its strip with no tabs at all`() {
        setScreen(tabs = emptyList())

        val root = composeTestRule.onNodeWithTag(ROOT_TAG).getUnclippedBoundsInRoot()
        val rail = composeTestRule.onNodeWithTag(WorkspaceNavigationRailDefaults.SURFACE_TEST_TAG)
            .getUnclippedBoundsInRoot()

        rail.bottom shouldBe root.bottom
        (rail.bottom - rail.top > 0.dp) shouldBe true
        composeTestRule.onNodeWithTag(WorkspaceNavigationRailDefaults.SURFACE_TEST_TAG).assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w1280dp-h720dp-land")
    fun `the start rail keeps its strip beside the pager`() {
        setScreen()

        val root = composeTestRule.onNodeWithTag(ROOT_TAG).getUnclippedBoundsInRoot()
        val rail = composeTestRule.onNodeWithTag(WorkspaceNavigationRailDefaults.SURFACE_TEST_TAG)
            .getUnclippedBoundsInRoot()
        val page = composeTestRule.onNodeWithTag(pageTag(firstTab.id)).getUnclippedBoundsInRoot()

        rail.left shouldBe root.left
        (rail.right - rail.left > 0.dp) shouldBe true
        (page.left >= rail.right) shouldBe true
        (page.right > page.left) shouldBe true
        composeTestRule.onAllNodesWithTag(WorkspaceNavigationRailDefaults.ITEM_TEST_TAG)[0].assertIsDisplayed()
    }

    @Test
    fun `Show turns the pager to that tab`() {
        setScreen()

        openRailMenu(1)
        pickFromMenu(R.string.workspace_pane_show_action)

        composeTestRule.onNodeWithTag(pageTag(secondTab.id)).assertIsDisplayed()
    }

    /**
     * Parked on the new-tab page, focus stays on the last tab, so selecting that same tab changes no
     * state at all. The pager has to come back anyway.
     */
    @Test
    fun `Show brings the focused tab back from the new-tab page`() {
        setScreen(focused = secondTab.id, onDemand = true)
        swipeOntoPlaceholder()

        openRailMenu(1)
        pickFromMenu(R.string.workspace_pane_show_action)

        composeTestRule.onNodeWithTag(pageTag(secondTab.id)).assertIsDisplayed()
    }

    @Test
    fun `closing the shown tab from the new-tab page puts its confirmation on screen`() {
        setScreen(focused = secondTab.id, onDemand = true)
        swipeOntoPlaceholder()

        openRailMenu(1)
        pickFromMenu(R.string.workspace_pane_close_action)

        composeTestRule.onNodeWithTag(pageTag(secondTab.id)).assertIsDisplayed()
        composeTestRule.onNodeWithText("Discard").assertIsDisplayed().performClick()
        settle()
        actions shouldContain WorkspaceScreenAction.HandleDialog(
            ManagerDialogAction.Resolve("close-${secondTab.id.shortTag}", confirmed = true),
        )
    }

    /** Closing another tab asks in a window dialog, so the pager has no reason to leave the new-tab page. */
    @Test
    fun `closing another tab from the new-tab page leaves the pager there`() {
        setScreen(focused = secondTab.id, onDemand = true)
        swipeOntoPlaceholder()

        openRailMenu(0)
        pickFromMenu(R.string.workspace_pane_close_action)

        globalDialogs.size shouldBe 1
        composeTestRule.onNodeWithTag(pageTag(secondTab.id)).assertIsNotDisplayed()
        composeTestRule.onNodeWithText("Discard").assertDoesNotExist()
    }

    /** What a rotation between rail placements or a window crossing the one-pane threshold does. */
    @Test
    fun `a pager composed afresh starts on the focused tab`() {
        panelMode = WorkspacePanelMode.DUAL_VERTICAL
        setScreen(focused = secondTab.id)
        composedPages.clear()

        composeTestRule.runOnIdle { panelMode = WorkspacePanelMode.SINGLE_RAIL }
        settle()

        composedPages.first() shouldBe secondTab.id
        composeTestRule.onNodeWithTag(pageTag(secondTab.id)).assertIsDisplayed()
    }

    /** The rail already marks the current tab, so the classic pager's switch card stays away. */
    @Test
    fun `the rail host shows no switch indicator`() {
        setScreen(settleAfter = false)
        composeTestRule.mainClock.advanceTimeByFrame()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText(context.getString(eu.darken.butler.common.R.string.common_x_of_y_label, 1, 2))
            .assertDoesNotExist()
    }

    /** Auto-pause goes by what the pager shows while it hosts the pane, and by assignments once it stops. */
    @Test
    fun `the pager publishes its page until the layout gains a second pane`() {
        setScreen()
        visibility.visibleIds() shouldBe setOf(firstTab.id)

        composeTestRule.runOnIdle { panelMode = WorkspacePanelMode.DUAL_VERTICAL }
        settle()

        visibility.visibleIds() shouldBe emptySet()
    }

    companion object {
        private const val ROOT_TAG = "rail.pager.root"

        private fun pageTag(id: Workspace.Id) = "rail.pager.page-${id.longTag}"
    }
}
