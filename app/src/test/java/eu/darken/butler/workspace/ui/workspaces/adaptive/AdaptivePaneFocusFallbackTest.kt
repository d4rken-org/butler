package eu.darken.butler.workspace.ui.workspaces.adaptive

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
import eu.darken.butler.workspace.ui.LocalWorkspacePageHosts
import eu.darken.butler.workspace.ui.LocalWorkspacePagerVisibility
import eu.darken.butler.workspace.ui.WorkspacePageHostEntry
import eu.darken.butler.workspace.ui.WorkspaceVisibilityTracker
import eu.darken.butler.workspace.ui.manager.WorkspaceDesign
import eu.darken.butler.workspace.ui.modal.WorkspaceBackHandler
import eu.darken.butler.workspace.ui.workspaces.AdaptiveWorkspaceLayout
import eu.darken.butler.workspace.ui.workspaces.asPaneInfo
import io.kotest.matchers.shouldBe
import org.junit.Test
import testhelpers.ComposeTest

/**
 * The single-with-rail layout while focus resolves to no tab at all.
 *
 * That state is reachable and nothing repairs it on its own - after a pane-local modal closes, after
 * picker-driven tab creation, after a session restore - and the pane boundary swallows every press
 * to request a focus that never arrives, leaving the visible pane tap-dead. With only one pane
 * nobody else can hold focus, so the pane accepts presses, while Back stays tied to focus that is
 * actually held: no pane may arm a back handler while nothing is focused.
 */
class AdaptivePaneFocusFallbackTest : ComposeTest() {

    private val tabA = Workspace.Id()

    private val infoA = Workspace.Info(
        id = tabA,
        type = Workspace.Type.EXPLORER,
        title = "Tab ${tabA.shortTag}".toCaString(),
        lifecycleState = Workspace.LifecycleState.Ready,
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
        focusedId: Workspace.Id?,
        focusedRootId: Workspace.Id?,
        host: RecordingHost,
        onReachedAppRoot: () -> Unit = {},
    ) {
        PreviewWrapper {
            CompositionLocalProvider(
                LocalWorkspacePageHosts provides mapOf(Workspace.Type.EXPLORER to host),
                LocalWorkspacePagerVisibility provides WorkspaceVisibilityTracker(),
            ) {
                // Stands in for MainActivity's press-back-again-to-exit prompt: registered above
                // everything else, so it only runs once nothing in the workspace tree consumed the
                // press.
                BackHandler(enabled = true) { onReachedAppRoot() }

                AdaptiveWorkspaceLayout(
                    design = WorkspaceDesign(
                        layout = WorkspaceDesign.Layout.SINGLE,
                        hasNavigationRail = true,
                    ),
                    workspaces = listOf(infoA),
                    selected = mapOf(0 to infoA.asPaneInfo()),
                    focusedId = focusedId,
                    focusedRootId = focusedRootId,
                    dividerPositions = DividerPositions(),
                    onDividerPositionsChange = {},
                    showPaneNumbers = false,
                    showPaneOverlay = false,
                    onPaneMenuToggle = {},
                    onScreenAction = {},
                    managerDialogStates = emptyMap(),
                    bannerStates = emptyMap(),
                    onDismissBanner = {},
                    clickToFocus = true,
                    onShareError = { _, _ -> },
                )
            }
        }
    }

    /** The rail's mascot animates on an endless loop, which never lets the clock idle. */
    private fun parkTheClock() {
        composeTestRule.mainClock.autoAdvance = false
    }

    private fun pressThePage() {
        // Pressed near the corner rather than via performClick(), which targets the node's centre.
        composeTestRule.onNodeWithTag(RecordingHost.tagFor(tabA)).performTouchInput {
            down(Offset(5f, 5f))
            up()
        }
        composeTestRule.waitForIdle()
    }

    /**
     * Nothing is focused, so no pane can claim the press for itself - and the request the boundary
     * makes instead is dropped on the floor here, exactly as it is in production while the state
     * that produced this focus keeps being republished.
     */
    @Test
    fun `a press reaches the sole pane while focus names no tab`() {
        val host = RecordingHost()
        parkTheClock()

        composeTestRule.setContent {
            Container(focusedId = null, focusedRootId = null, host = host)
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag(RecordingHost.tagFor(tabA)).assertIsDisplayed()

        pressThePage()

        composeTestRule.runOnIdle { host.clicks[tabA] shouldBe 1 }
    }

    /**
     * The other reachable shape of the same state: focus names a workspace whose caller chain does
     * not resolve, so it belongs to no tab and no pane can be focused by it either.
     */
    @Test
    fun `a press reaches the sole pane while focus names a dangling child`() {
        val host = RecordingHost()
        parkTheClock()

        composeTestRule.setContent {
            Container(focusedId = Workspace.Id(), focusedRootId = null, host = host)
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag(RecordingHost.tagFor(tabA)).assertIsDisplayed()

        pressThePage()

        composeTestRule.runOnIdle { host.clicks[tabA] shouldBe 1 }
    }

    /**
     * The other half of the split: accepting presses must not arm anything Back can reach. A page
     * that answered Back here would navigate or close a workspace that nothing has focused, so the
     * press belongs to the app root.
     */
    @Test
    fun `back is not dispatched into the sole pane while focus names no tab`() {
        val host = RecordingHost()
        var reachedAppRoot = 0
        var dispatcher: OnBackPressedDispatcher? = null
        parkTheClock()

        composeTestRule.setContent {
            dispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
            Container(
                focusedId = null,
                focusedRootId = null,
                host = host,
                onReachedAppRoot = { reachedAppRoot++ },
            )
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag(RecordingHost.tagFor(tabA)).assertIsDisplayed()

        composeTestRule.runOnIdle { dispatcher!!.onBackPressed() }
        composeTestRule.waitForIdle()

        composeTestRule.runOnIdle {
            (host.backPresses[tabA] ?: 0) shouldBe 0
            reachedAppRoot shouldBe 1
        }
    }

    /**
     * Control for the press tests: the very same press on the very same page is accepted once focus
     * really is on its tab, so "the press arrived" there is about the focus state and not about the
     * press location happening to land on something that always accepts.
     */
    @Test
    fun `a press reaches the sole pane once focus names its tab`() {
        val host = RecordingHost()
        parkTheClock()

        composeTestRule.setContent {
            Container(focusedId = tabA, focusedRootId = tabA, host = host)
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag(RecordingHost.tagFor(tabA)).assertIsDisplayed()

        pressThePage()

        composeTestRule.runOnIdle { host.clicks[tabA] shouldBe 1 }
    }

    /**
     * Control for the Back test: the very same page's handler does answer Back once focus really is
     * on its tab, so "nothing answered" there is about the focus state and not about the page never
     * having registered a handler.
     */
    @Test
    fun `back reaches the sole pane once focus names its tab`() {
        val host = RecordingHost()
        var reachedAppRoot = 0
        var dispatcher: OnBackPressedDispatcher? = null
        var focused by mutableStateOf<Workspace.Id?>(null)
        parkTheClock()

        composeTestRule.setContent {
            dispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
            Container(
                focusedId = focused,
                focusedRootId = focused,
                host = host,
                onReachedAppRoot = { reachedAppRoot++ },
            )
        }
        composeTestRule.waitForIdle()

        composeTestRule.runOnIdle { focused = tabA }
        // The clock is parked, so a state change needs a frame before it has recomposed.
        composeTestRule.mainClock.advanceTimeByFrame()
        composeTestRule.waitForIdle()

        composeTestRule.runOnIdle { dispatcher!!.onBackPressed() }
        composeTestRule.waitForIdle()

        composeTestRule.runOnIdle {
            host.backPresses[tabA] shouldBe 1
            reachedAppRoot shouldBe 0
        }
    }
}
