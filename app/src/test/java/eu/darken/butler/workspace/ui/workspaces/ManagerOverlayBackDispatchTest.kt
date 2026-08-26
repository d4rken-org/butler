package eu.darken.butler.workspace.ui.workspaces

import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.WorkspaceRemote
import eu.darken.butler.workspace.ui.LocalWorkspacePageHosts
import eu.darken.butler.workspace.ui.WorkspacePageHostEntry
import eu.darken.butler.workspace.ui.manager.WorkspaceDesign
import eu.darken.butler.workspace.ui.modal.WorkspaceBackHandler
import eu.darken.butler.workspace.ui.workspaces.adaptive.DividerPositions
import eu.darken.butler.workspace.ui.workspaces.classic.ClassicWorkspaceContainer
import io.kotest.matchers.shouldBe
import org.junit.Test
import testhelpers.ComposeTest

/**
 * Back dispatch while the tab manager overlay covers the workspace.
 *
 * Regression cover for back closing the focused TAB instead of dismissing the overlay: page back
 * handlers are composed deeper than the overlay's dismisser, so LIFO alone hands them the press.
 * What prevents it is each layout container marking its panes unfocused while the overlay is up,
 * which turns off `LocalLayerActive` and with it every [WorkspaceBackHandler] in the pane. Both
 * containers carry that wiring independently, so both are exercised here — the bug was originally
 * reported on a tablet, i.e. the adaptive path.
 *
 * The real containers, panes, layers, [WorkspaceBackHandler] and the production
 * [ManagerOverlayBackHandler] are composed; only the page behind the host is a stand-in, since the
 * real ones instantiate Hilt ViewModels.
 *
 * Known gap: this enters at the container, so it cannot see the `WorkspacesScreenHost` →
 * `WorkspaceScreen` → container forwarding of the overlay's visibility. A regression in that
 * plumbing, or in which state the host hands to [ManagerOverlayBackHandler], stays invisible here.
 */
class ManagerOverlayBackDispatchTest : ComposeTest() {

    private val tabId = Workspace.Id()

    private val tabInfo = Workspace.Info(
        id = tabId,
        type = Workspace.Type.EXPLORER,
        title = "Explorer".toCaString(),
        lifecycleState = Workspace.LifecycleState.Ready,
    )

    private enum class Container { CLASSIC, ADAPTIVE }

    /** Stands in for a page whose top-level back consumes the press and closes its tab — the worst case for the dispatch order under test. */
    private class ClosingPageHost(private val onCloseTab: () -> Unit) : WorkspacePageHostEntry {

        @Composable
        override fun Content(id: Workspace.Id, design: WorkspaceDesign) {
            WorkspaceBackHandler(enabled = true) { onCloseTab() }
        }

        @Composable
        override fun Overlays(id: Workspace.Id, design: WorkspaceDesign) {
        }
    }

    private class Outcome {
        var tabClosed = false
        var overlayDismissed = false
    }

    private fun pressBack(container: Container, overlayVisible: Boolean): Outcome {
        val outcome = Outcome()
        var dispatcher: OnBackPressedDispatcher? = null

        val pageHosts = mapOf<Workspace.Type, WorkspacePageHostEntry>(
            Workspace.Type.EXPLORER to ClosingPageHost { outcome.tabClosed = true },
        )

        val state = WorkspacesViewModel.State(
            state = WorkspaceRemote.State(infos = listOf(tabInfo)),
            focusedWorkspace = tabId,
            selectedWorkspaces = mapOf(0 to tabId),
            isUpgraded = true,
            swipeGesturesEnabled = false,
            onDemandWorkspaceCreation = false,
        )

        composeTestRule.setContent {
            dispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
            PreviewWrapper {
                CompositionLocalProvider(LocalWorkspacePageHosts provides pageHosts) {
                    // Registered where WorkspacesScreenHost registers it: above the workspace
                    // content, so it loses every LIFO race it takes part in.
                    ManagerOverlayBackHandler(
                        isOverlayVisible = overlayVisible,
                        onDismiss = { outcome.overlayDismissed = true },
                    )

                    when (container) {
                        Container.CLASSIC -> ClassicWorkspaceContainer(
                            state = state,
                            isOverlayVisible = overlayVisible,
                            onWorkspaceScreenAction = {},
                            managerDialogStates = emptyMap(),
                            bannerStates = emptyMap(),
                            onDismissBanner = {},
                            onShareError = { _, _ -> },
                        )

                        Container.ADAPTIVE -> AdaptiveWorkspaceLayout(
                            design = WorkspaceDesign(layout = WorkspaceDesign.Layout.DUAL_VERTICAL),
                            workspaces = state.tabWorkspaces,
                            selected = mapOf(0 to tabInfo.asPaneInfo()),
                            focusedId = tabId,
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
                            isOverlayVisible = overlayVisible,
                            onShareError = { _, _ -> },
                        )
                    }
                }
            }
        }

        composeTestRule.runOnIdle { dispatcher!!.onBackPressed() }
        composeTestRule.waitForIdle()

        return outcome
    }

    @Test
    fun `single pane - back dismisses the overlay instead of closing the focused tab`() {
        val outcome = pressBack(Container.CLASSIC, overlayVisible = true)

        outcome.tabClosed shouldBe false
        outcome.overlayDismissed shouldBe true
    }

    /**
     * The mirror image, without which the assertion above would also pass on a page handler that
     * never registered at all.
     */
    @Test
    fun `single pane - back reaches the page while no overlay is up`() {
        val outcome = pressBack(Container.CLASSIC, overlayVisible = false)

        outcome.tabClosed shouldBe true
        outcome.overlayDismissed shouldBe false
    }

    @Test
    fun `multi pane - back dismisses the overlay instead of closing the focused tab`() {
        val outcome = pressBack(Container.ADAPTIVE, overlayVisible = true)

        outcome.tabClosed shouldBe false
        outcome.overlayDismissed shouldBe true
    }

    @Test
    fun `multi pane - back reaches the page while no overlay is up`() {
        val outcome = pressBack(Container.ADAPTIVE, overlayVisible = false)

        outcome.tabClosed shouldBe true
        outcome.overlayDismissed shouldBe false
    }
}
