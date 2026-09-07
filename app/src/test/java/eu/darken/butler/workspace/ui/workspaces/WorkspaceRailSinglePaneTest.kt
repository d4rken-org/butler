package eu.darken.butler.workspace.ui.workspaces

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import eu.darken.butler.R
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.WorkspaceRemote
import eu.darken.butler.workspace.core.layout.WorkspacePanelMode
import eu.darken.butler.workspace.ui.LocalWorkspacePageHosts
import eu.darken.butler.workspace.ui.WorkspacePageHostEntry
import eu.darken.butler.workspace.ui.manager.WorkspaceDesign
import eu.darken.butler.workspace.ui.workspaces.adaptive.WorkspaceNavigationRailDefaults
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import org.junit.Test
import org.robolectric.annotation.Config
import testhelpers.ComposeTest

/**
 * The single-with-rail panel mode: one pane, rendered through the adaptive layout so the rail is
 * composed beside it and follows the window's placement rule like any multi-pane layout does.
 *
 * The plain single mode is asserted alongside it, because that one still routes to the classic
 * pager and must keep composing no rail at all.
 */
class WorkspaceRailSinglePaneTest : ComposeTest() {

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

    /** The real page instantiates Hilt ViewModels; this one only marks out the pane's bounds. */
    private object PaneMarkerHost : WorkspacePageHostEntry {
        @Composable
        override fun Content(id: Workspace.Id, design: WorkspaceDesign) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .testTag(PANE_TAG),
            )
        }

        @Composable
        override fun Overlays(id: Workspace.Id, design: WorkspaceDesign) = Unit
    }

    private fun state(mode: WorkspacePanelMode) = WorkspacesViewModel.State(
        state = WorkspaceRemote.State(
            infos = listOf(firstTab, secondTab),
            portraitPanelMode = mode,
            landscapePanelMode = mode,
        ),
        focusedWorkspace = firstTab.id,
        selectedWorkspaces = mapOf(0 to firstTab.id),
        visiblePaneSelections = mapOf(0 to firstTab.id),
        isUpgraded = true,
        swipeGesturesEnabled = false,
        onDemandWorkspaceCreation = false,
        currentPaneCount = 1,
    )

    private fun setScreen(
        mode: WorkspacePanelMode = WorkspacePanelMode.SINGLE_RAIL,
        onScreenAction: (WorkspaceScreenAction) -> Unit = {},
    ) {
        // The Butler button's mascot animates on an endless loop, which never lets the clock idle.
        // Nothing asserted here needs frames.
        composeTestRule.mainClock.autoAdvance = false
        composeTestRule.setContent {
            PreviewWrapper {
                CompositionLocalProvider(
                    LocalWorkspacePageHosts provides mapOf(Workspace.Type.EXPLORER to PaneMarkerHost),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .testTag(ROOT_TAG),
                    ) {
                        WorkspaceScreen(
                            state = state(mode),
                            managerDialogStates = emptyMap(),
                            onScreenAction = onScreenAction,
                        )
                    }
                }
            }
        }
        composeTestRule.waitForIdle()
    }

    private fun assertTheSolePaneHasHeight() {
        val panes = composeTestRule.onAllNodesWithTag(PANE_TAG, useUnmergedTree = true)
        panes.assertCountEquals(1)
        val bounds = panes[0].getUnclippedBoundsInRoot()
        (bounds.bottom - bounds.top > 0.dp) shouldBe true
    }

    @Test
    @Config(qualifiers = "w720dp-h1280dp-port")
    fun `a portrait window puts the rail along the bottom edge`() {
        setScreen()

        val root = composeTestRule.onNodeWithTag(ROOT_TAG).getUnclippedBoundsInRoot()
        val rail = composeTestRule.onNodeWithTag(WorkspaceNavigationRailDefaults.SURFACE_TEST_TAG)
            .getUnclippedBoundsInRoot()

        rail.bottom shouldBe root.bottom
        rail.left shouldBe root.left
        rail.right shouldBe root.right
        (rail.top > root.top) shouldBe true

        assertTheSolePaneHasHeight()
    }

    @Test
    @Config(qualifiers = "w1280dp-h720dp-land")
    fun `a landscape window keeps the rail on the start edge`() {
        setScreen()

        val root = composeTestRule.onNodeWithTag(ROOT_TAG).getUnclippedBoundsInRoot()
        val rail = composeTestRule.onNodeWithTag(WorkspaceNavigationRailDefaults.SURFACE_TEST_TAG)
            .getUnclippedBoundsInRoot()

        rail.left shouldBe root.left
        rail.top shouldBe root.top
        rail.bottom shouldBe root.bottom
        (rail.right < root.right) shouldBe true

        assertTheSolePaneHasHeight()
    }

    @Test
    @Config(qualifiers = "w720dp-h1280dp-port")
    fun `the plain single mode still composes no rail`() {
        setScreen(mode = WorkspacePanelMode.SINGLE)

        composeTestRule.onNodeWithTag(WorkspaceNavigationRailDefaults.SURFACE_TEST_TAG)
            .assertDoesNotExist()
    }

    /**
     * With one pane there is nothing to swap the entry against, so the menu's single assign item
     * selects the tab into the pane instead of rearranging assignments around it.
     */
    @Test
    @Config(qualifiers = "w720dp-h1280dp-port")
    fun `Show selects the entry into the only pane`() {
        val actions = mutableListOf<WorkspaceScreenAction>()
        setScreen(onScreenAction = { actions.add(it) })
        // The reveal scroll holds the list's scroll mutex until its first frame; a touch on a
        // scrolling list starts a drag instead of a click.
        composeTestRule.mainClock.advanceTimeByFrame()
        composeTestRule.waitForIdle()

        composeTestRule.onAllNodesWithTag(WorkspaceNavigationRailDefaults.ITEM_TEST_TAG)[1]
            .performClick()
        // The clock is parked, so a state change needs a frame before it has recomposed.
        composeTestRule.mainClock.advanceTimeByFrame()
        composeTestRule.waitForIdle()

        composeTestRule
            .onNodeWithText(context.getString(R.string.workspace_pane_show_action))
            .performClick()
        composeTestRule.mainClock.advanceTimeByFrame()
        composeTestRule.waitForIdle()

        actions shouldContain WorkspaceScreenAction.Select(secondTab.id)
        actions.none { it is WorkspaceScreenAction.SelectMultiple } shouldBe true
    }

    companion object {
        private const val ROOT_TAG = "rail.single.root"
        private const val PANE_TAG = "rail.single.pane"
    }
}
