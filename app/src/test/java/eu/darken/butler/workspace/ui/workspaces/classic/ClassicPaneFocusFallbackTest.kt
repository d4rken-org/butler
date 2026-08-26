package eu.darken.butler.workspace.ui.workspaces.classic

import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.down
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.up
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.WorkspaceRemote
import eu.darken.butler.workspace.ui.LocalWorkspacePageHosts
import eu.darken.butler.workspace.ui.LocalWorkspacePagerVisibility
import eu.darken.butler.workspace.ui.WorkspacePageHostEntry
import eu.darken.butler.workspace.ui.WorkspacePageManager
import eu.darken.butler.workspace.ui.WorkspaceVisibilityTracker
import eu.darken.butler.workspace.ui.manager.WorkspaceDesign
import eu.darken.butler.workspace.ui.modal.WorkspaceBackHandler
import eu.darken.butler.workspace.ui.workspaces.WorkspaceScreenAction
import eu.darken.butler.workspace.ui.workspaces.WorkspacesViewModel
import io.kotest.matchers.shouldBe
import org.junit.Test
import testhelpers.ComposeTest

/**
 * The classic pager while focus resolves to no tab at all.
 *
 * That state is reachable and nothing repairs it on its own — after a pane-local modal closes, after
 * picker-driven tab creation, after a session restore — and the pane boundary swallows every press
 * to request a focus that never arrives, leaving the visible page tap-dead. The page the pager rests
 * on therefore accepts presses, while Back stays tied to focus that is actually held: no page may
 * arm a back handler while nothing is focused.
 *
 * On-demand creation is off in every fixture, as in [ClassicWorkspacePagerTest]: the trailing
 * placeholder animates forever and composing it would stall the Robolectric clock.
 */
class ClassicPaneFocusFallbackTest : ComposeTest() {

    private val tabA = Workspace.Id()
    private val tabB = Workspace.Id()

    private fun tab(id: Workspace.Id) = Workspace.Info(
        id = id,
        type = Workspace.Type.EXPLORER,
        title = "Tab ${id.shortTag}".toCaString(),
        lifecycleState = Workspace.LifecycleState.Ready,
    )

    private fun state(
        infos: List<Workspace.Info>,
        focused: Workspace.Id?,
        selected: Map<Int, Workspace.Id>,
    ) = WorkspacesViewModel.State(
        state = WorkspaceRemote.State(infos = infos),
        focusedWorkspace = focused,
        selectedWorkspaces = selected,
        visiblePaneSelections = WorkspacePageManager.State(
            selectedWorkspaces = selected,
            currentPaneCount = 1,
        ).visiblePaneAssignments,
        isUpgraded = true,
        swipeGesturesEnabled = true,
        onDemandWorkspaceCreation = false,
        currentPaneCount = 1,
    )

    /**
     * Stands in for a workspace page: clickable content plus a back handler of its own, which is
     * what every real page installs. Both are what the two questions here are asked of.
     */
    private class RecordingHost : WorkspacePageHostEntry {
        val clicks = mutableMapOf<Workspace.Id, Int>()
        val backPresses = mutableMapOf<Workspace.Id, Int>()

        @Composable
        override fun Content(id: Workspace.Id, design: WorkspaceDesign) {
            WorkspaceBackHandler(enabled = true) { backPresses[id] = (backPresses[id] ?: 0) + 1 }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .testTag(tagFor(id))
                    .clickable { clicks[id] = (clicks[id] ?: 0) + 1 },
            )
        }

        @Composable
        override fun Overlays(id: Workspace.Id, design: WorkspaceDesign) = Unit

        companion object {
            fun tagFor(id: Workspace.Id) = "page-${id.shortTag}"
        }
    }

    @Composable
    private fun Container(
        state: WorkspacesViewModel.State,
        host: RecordingHost,
        onAction: (WorkspaceScreenAction) -> Unit,
        onReachedAppRoot: () -> Unit = {},
    ) {
        PreviewWrapper {
            CompositionLocalProvider(
                LocalWorkspacePageHosts provides mapOf(Workspace.Type.EXPLORER to host),
                LocalWorkspacePagerVisibility provides WorkspaceVisibilityTracker(),
            ) {
                // Stands in for MainActivity's press-back-again-to-exit prompt, as in
                // ClassicWorkspacePagerTest: registered above everything else, so it only runs once
                // nothing in the workspace tree consumed the press.
                BackHandler(enabled = true) { onReachedAppRoot() }

                ClassicWorkspaceContainer(
                    state = state,
                    onWorkspaceScreenAction = onAction,
                    managerDialogStates = emptyMap(),
                    bannerStates = emptyMap(),
                    onDismissBanner = {},
                    paneLocalModalChains = state.paneLocalModalChains,
                    onShareError = { _, _ -> },
                )
            }
        }
    }

    /**
     * Nothing is focused, so no pane can claim the press for itself — and the request the boundary
     * makes instead is dropped on the floor here, exactly as it is in production while the state
     * that produced this focus keeps being republished.
     */
    @Test
    fun `a press reaches the page the pager rests on while focus names no tab`() {
        val host = RecordingHost()
        val infos = listOf(tab(tabA), tab(tabB))

        composeTestRule.setContent {
            Container(
                state(infos, focused = null, selected = mapOf(0 to tabA)),
                host,
                onAction = {},
            )
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag(RecordingHost.tagFor(tabA)).assertIsDisplayed()

        // Pressed near the corner, not via performClick(): that targets the node's centre, and
        // WorkspaceSwitchIndicator is composed centred over the pager for as long as more than one
        // tab exists. It is a Card, so its Surface installs a pointer barrier that consumes the
        // press before the page's clickable sees it, and under Robolectric the indicator's 1s
        // auto-hide has not fired at press time.
        composeTestRule.onNodeWithTag(RecordingHost.tagFor(tabA)).performTouchInput {
            down(Offset(5f, 5f))
            up()
        }
        composeTestRule.waitForIdle()

        composeTestRule.runOnIdle { host.clicks[tabA] shouldBe 1 }
    }

    /**
     * Control for the test above: the very same press on the very same page is accepted once focus
     * really is on its tab, so "the press arrived" there is about the focus state and not about the
     * press location happening to land on something that always accepts.
     */
    @Test
    fun `a press reaches that page once focus names its tab`() {
        val host = RecordingHost()
        val infos = listOf(tab(tabA), tab(tabB))

        composeTestRule.setContent {
            Container(
                state(infos, focused = tabA, selected = mapOf(0 to tabA)),
                host,
                onAction = {},
            )
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag(RecordingHost.tagFor(tabA)).assertIsDisplayed()

        composeTestRule.onNodeWithTag(RecordingHost.tagFor(tabA)).performTouchInput {
            down(Offset(5f, 5f))
            up()
        }
        composeTestRule.waitForIdle()

        composeTestRule.runOnIdle { host.clicks[tabA] shouldBe 1 }
    }

    /**
     * The other half of the split: accepting presses must not arm anything Back can reach. A page
     * that answered Back here would navigate or close a workspace that nothing has focused, and the
     * container's own fallback is disarmed too (it has no focused tab to return to), so the press
     * belongs to the app root.
     */
    @Test
    fun `back is not dispatched into that page while focus names no tab`() {
        val host = RecordingHost()
        val infos = listOf(tab(tabA), tab(tabB))
        var reachedAppRoot = 0
        var dispatcher: OnBackPressedDispatcher? = null

        composeTestRule.setContent {
            dispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
            Container(
                state(infos, focused = null, selected = mapOf(0 to tabA)),
                host,
                onAction = {},
                onReachedAppRoot = { reachedAppRoot++ },
            )
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag(RecordingHost.tagFor(tabA)).assertIsDisplayed()

        composeTestRule.runOnIdle { dispatcher!!.onBackPressed() }
        composeTestRule.waitForIdle()

        composeTestRule.runOnIdle {
            host.backPresses[tabA] shouldBe null
            reachedAppRoot shouldBe 1
        }
    }

    /**
     * Control for the test above: the very same page's handler does answer Back once focus really
     * is on its tab, so "nothing answered" there is about the focus state and not about the page
     * never having registered a handler.
     */
    @Test
    fun `back reaches that page once focus names its tab`() {
        val host = RecordingHost()
        val infos = listOf(tab(tabA), tab(tabB))
        var reachedAppRoot = 0
        var dispatcher: OnBackPressedDispatcher? = null
        var current by mutableStateOf(state(infos, focused = null, selected = mapOf(0 to tabA)))

        composeTestRule.setContent {
            dispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
            Container(current, host, onAction = {}, onReachedAppRoot = { reachedAppRoot++ })
        }
        composeTestRule.waitForIdle()

        composeTestRule.runOnIdle {
            current = state(infos, focused = tabA, selected = mapOf(0 to tabA))
        }
        composeTestRule.waitForIdle()

        composeTestRule.runOnIdle { dispatcher!!.onBackPressed() }
        composeTestRule.waitForIdle()

        composeTestRule.runOnIdle {
            host.backPresses[tabA] shouldBe 1
            reachedAppRoot shouldBe 0
        }
    }
}
