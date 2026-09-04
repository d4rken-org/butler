package eu.darken.butler.workspace.ui.workspaces

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.WorkspaceRemote
import eu.darken.butler.workspace.core.layout.WorkspacePanelMode
import eu.darken.butler.workspace.ui.LocalWorkspacePageHosts
import eu.darken.butler.workspace.ui.WorkspacePageHostEntry
import eu.darken.butler.workspace.ui.manager.WorkspaceDesign
import eu.darken.butler.workspace.ui.workspaces.adaptive.WorkspaceNavigationRailDefaults
import io.kotest.matchers.shouldBe
import org.junit.Test
import org.robolectric.annotation.Config
import testhelpers.ComposeTest

/**
 * Which window edge the rail lands on is decided by [rememberWorkspaceDesign] from the window's
 * orientation, so the whole screen is driven rather than the layout below it: [AdaptiveWorkspaceLayout]
 * takes the design as a parameter and never reads the configuration itself, and Robolectric's default
 * compact width resolves to SINGLE, which composes no rail at all.
 *
 * The rail's height is asserted as well as its edge: it is the unweighted child of the layout's
 * column, so a descendant that fills the height it is offered would take the whole window and leave
 * the panes measuring zero - a rail on the right edge with nothing above it.
 */
class WorkspaceRailPlacementTest : ComposeTest() {

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

    private fun state() = WorkspacesViewModel.State(
        state = WorkspaceRemote.State(
            infos = listOf(firstTab, secondTab),
            // Explicit per orientation: an AUTO mode would resolve by window size and could hand
            // both cases the same layout, which is not what is under test.
            portraitPanelMode = WorkspacePanelMode.DUAL_HORIZONTAL,
            landscapePanelMode = WorkspacePanelMode.DUAL_VERTICAL,
        ),
        focusedWorkspace = firstTab.id,
        selectedWorkspaces = mapOf(0 to firstTab.id, 1 to secondTab.id),
        visiblePaneSelections = mapOf(0 to firstTab.id, 1 to secondTab.id),
        isUpgraded = true,
        swipeGesturesEnabled = false,
        onDemandWorkspaceCreation = false,
    )

    private fun setScreen() {
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
                            state = state(),
                            managerDialogStates = emptyMap(),
                            onScreenAction = {},
                        )
                    }
                }
            }
        }
        composeTestRule.waitForIdle()
    }

    private fun assertEveryPaneHasHeight() {
        val panes = composeTestRule.onAllNodesWithTag(PANE_TAG, useUnmergedTree = true)
        panes.assertCountEquals(2)
        repeat(2) { index ->
            val bounds = panes[index].getUnclippedBoundsInRoot()
            (bounds.bottom - bounds.top > 0.dp) shouldBe true
        }
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

        assertEveryPaneHasHeight()
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

        assertEveryPaneHasHeight()
    }

    companion object {
        private const val ROOT_TAG = "rail.placement.root"
        private const val PANE_TAG = "rail.placement.pane"
    }
}
