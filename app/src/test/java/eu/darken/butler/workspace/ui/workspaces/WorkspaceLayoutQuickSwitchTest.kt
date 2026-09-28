package eu.darken.butler.workspace.ui.workspaces

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.compose.ButlerMascotMode
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.WorkspaceAction
import eu.darken.butler.workspace.core.WorkspaceRemote
import eu.darken.butler.workspace.core.layout.WorkspacePanelMode
import eu.darken.butler.workspace.ui.LocalWorkspacePageHosts
import eu.darken.butler.workspace.ui.WorkspacePageHostEntry
import eu.darken.butler.workspace.ui.manager.LocalWorkspaceButtonProvider
import eu.darken.butler.workspace.ui.manager.WorkspaceButton
import eu.darken.butler.workspace.ui.manager.WorkspaceButtonDefaults
import eu.darken.butler.workspace.ui.manager.WorkspaceButtonProvider
import eu.darken.butler.workspace.ui.manager.WorkspaceButtonViewModel
import eu.darken.butler.workspace.ui.manager.WorkspaceDesign
import eu.darken.butler.workspace.ui.template.QuickCreateItem
import eu.darken.butler.workspace.ui.workspaces.adaptive.WorkspaceNavigationRailDefaults
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import org.junit.Test
import org.robolectric.annotation.Config
import testhelpers.ComposeTest

/**
 * Classic to adaptive and back without Settings: the page's own Butler button carries the Layout
 * row in classic mode, the rail's carries it in adaptive mode, and each write swaps which of the
 * two buttons is on screen.
 */
class WorkspaceLayoutQuickSwitchTest : ComposeTest() {

    private val tab = Workspace.Info(
        id = Workspace.Id(),
        type = Workspace.Type.EXPLORER,
        title = "Explorer".toCaString(),
        lifecycleState = Workspace.LifecycleState.Ready,
    )

    /** Composes a Butler button only where there is no rail, as the toolbar cards do. */
    private object ToolbarButtonHost : WorkspacePageHostEntry {
        @Composable
        override fun Content(id: Workspace.Id, design: WorkspaceDesign) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .testTag(PANE_TAG),
            ) {
                if (!design.hasNavigationRail) {
                    WorkspaceButton(
                        modifier = Modifier.testTag(PAGE_BUTTON_TAG),
                        currentWorkspaceId = id,
                        mascotVariant = ButlerMascotMode.Static.Normal(),
                    )
                }
            }
        }

        @Composable
        override fun Overlays(id: Workspace.Id, design: WorkspaceDesign) = Unit
    }

    /** Persists like the real provider: the write is what the screen and the menus read back. */
    private class StoringButtonProvider(initial: WorkspacePanelMode) : WorkspaceButtonProvider {
        val portraitMode = MutableStateFlow(initial)
        val writes = mutableListOf<Pair<Boolean, WorkspacePanelMode>>()

        override val state: Flow<WorkspaceButtonViewModel.State> = portraitMode.map {
            WorkspaceButtonViewModel.State(workspaceCount = 1, portraitPanelMode = it)
        }

        override fun setPanelMode(landscape: Boolean, mode: WorkspacePanelMode, recommendedPaneCount: Int) {
            writes += landscape to mode
            if (!landscape) portraitMode.value = mode
        }

        override fun executeWorkspaceAction(action: WorkspaceAction) = Unit
        override fun navToWorkspaceManager() = Unit
        override fun navToSettings() = Unit
        override fun navToUpgradeButler() = Unit
        override fun createWorkspace(item: QuickCreateItem) = Unit
        override fun createTemplatesWorkspace() = Unit
    }

    private fun state(portraitMode: WorkspacePanelMode) = WorkspacesViewModel.State(
        state = WorkspaceRemote.State(
            infos = listOf(tab),
            portraitPanelMode = portraitMode,
            landscapePanelMode = WorkspacePanelMode.AUTO,
        ),
        focusedWorkspace = tab.id,
        selectedWorkspaces = mapOf(0 to tab.id),
        visiblePaneSelections = mapOf(0 to tab.id),
        isUpgraded = true,
        swipeGesturesEnabled = false,
        onDemandWorkspaceCreation = false,
        currentPaneCount = 1,
    )

    private fun setScreen(provider: StoringButtonProvider) {
        // The rail's Butler button animates its mascot on an endless loop, which never lets the
        // clock idle.
        composeTestRule.mainClock.autoAdvance = false
        composeTestRule.setContent {
            PreviewWrapper {
                CompositionLocalProvider(
                    LocalWorkspacePageHosts provides mapOf(Workspace.Type.EXPLORER to ToolbarButtonHost),
                    LocalWorkspaceButtonProvider provides provider,
                ) {
                    val portraitMode by provider.portraitMode.collectAsState()
                    WorkspaceScreen(
                        state = state(portraitMode),
                        managerDialogStates = emptyMap(),
                        onScreenAction = {},
                    )
                }
            }
        }
        settle()
    }

    /** The clock is parked, so menu and dialog transitions only finish when it is moved. */
    private fun settle() {
        composeTestRule.mainClock.advanceTimeBy(1_000)
        composeTestRule.waitForIdle()
    }

    private fun pickLayout(label: String) {
        composeTestRule.onNodeWithTag(WorkspaceButtonDefaults.TEST_TAG).performClick()
        settle()
        composeTestRule.onNodeWithText("Layout").performClick()
        settle()
        composeTestRule.onNodeWithText(label).performScrollTo().performClick()
        settle()
    }

    private fun assertClassic() {
        composeTestRule.onNodeWithTag(WorkspaceNavigationRailDefaults.SURFACE_TEST_TAG).assertDoesNotExist()
        composeTestRule.onNodeWithTag(PAGE_BUTTON_TAG, useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithTag(PANE_TAG, useUnmergedTree = true).assertExists()
    }

    private fun assertLayoutDialogClosed() {
        composeTestRule.onNodeWithText("Automatic").assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = "w360dp-h640dp-port")
    fun `the page and rail butler menus switch classic to adaptive and back`() {
        val provider = StoringButtonProvider(WorkspacePanelMode.SINGLE)
        setScreen(provider)
        assertClassic()

        pickLayout("Adaptive")

        provider.writes shouldBe listOf(false to WorkspacePanelMode.ADAPTIVE)
        assertLayoutDialogClosed()
        composeTestRule.onNodeWithTag(WorkspaceNavigationRailDefaults.SURFACE_TEST_TAG).assertExists()
        composeTestRule.onNodeWithTag(PAGE_BUTTON_TAG, useUnmergedTree = true).assertDoesNotExist()
        composeTestRule.onNodeWithTag(PANE_TAG, useUnmergedTree = true).assertExists()

        pickLayout("Classic")

        provider.writes shouldBe listOf(
            false to WorkspacePanelMode.ADAPTIVE,
            false to WorkspacePanelMode.SINGLE,
        )
        assertLayoutDialogClosed()
        assertClassic()
    }

    companion object {
        private const val PANE_TAG = "quickswitch.pane"
        private const val PAGE_BUTTON_TAG = "quickswitch.pageButton"
    }
}
