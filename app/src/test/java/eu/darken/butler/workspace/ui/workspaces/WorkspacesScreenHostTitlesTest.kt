package eu.darken.butler.workspace.ui.workspaces

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.test.core.app.ApplicationProvider
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.flow.SingleEventFlow
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.WorkspaceRemote
import eu.darken.butler.workspace.core.layout.WorkspacePanelMode
import eu.darken.butler.workspace.core.undo.ClosedWorkspaceFeedback
import eu.darken.butler.workspace.ui.LocalWorkspaceTitles
import eu.darken.butler.workspace.ui.WorkspacePageHostEntry
import eu.darken.butler.workspace.ui.WorkspacePageManager
import eu.darken.butler.workspace.ui.WorkspaceVisibilityTracker
import eu.darken.butler.workspace.ui.floatingbar.WorkspaceBarCollapseStates
import eu.darken.butler.workspace.ui.manager.WorkspaceButtonViewModel
import eu.darken.butler.workspace.ui.manager.WorkspaceDesign
import eu.darken.butler.workspace.ui.manager.WorkspaceManagerViewModel
import eu.darken.butler.workspace.ui.scroll.WorkspaceScrollPositions
import eu.darken.butler.workspace.ui.tabLabel
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Test
import testhelpers.ComposeTest

/**
 * [WorkspacesScreenHost] is the only production provider of [LocalWorkspaceTitles]. The local is
 * three-valued and an unprovided registry means "this composition knows nothing about workspaces",
 * so dropping the provider silently removes every field that names a workspace it does not host -
 * no crash and no log. Composed through the real host, not a stand-in provider, for that reason.
 */
class WorkspacesScreenHostTitlesTest : ComposeTest() {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private val namedId = Workspace.Id()
    private val unnamedId = Workspace.Id()

    private fun info(id: Workspace.Id, customTitle: String? = null) = Workspace.Info(
        id = id,
        type = Workspace.Type.EXPLORER,
        title = "Explorer".toCaString(),
        lifecycleState = Workspace.LifecycleState.Ready,
        customTitle = customTitle,
    )

    // A renamed tab and a default-named one: the two branches tabLabel resolves through
    private val infos = listOf(info(namedId, customTitle = "Camera dump"), info(unnamedId))

    /** The real Explorer page instantiates Hilt ViewModels, so the occupied pane gets a stand-in. */
    private class TitleReadingPageHost : WorkspacePageHostEntry {
        var compositions = 0
        var seen: Map<Workspace.Id, String>? = null

        @Composable
        override fun Content(id: Workspace.Id, design: WorkspaceDesign) {
            compositions++
            seen = LocalWorkspaceTitles.current
        }

        @Composable
        override fun Overlays(id: Workspace.Id, design: WorkspaceDesign) {
        }
    }

    private val pageHost = TitleReadingPageHost()

    private val screenState = WorkspacesViewModel.State(
        state = WorkspaceRemote.State(
            infos = infos,
            portraitPanelMode = WorkspacePanelMode.SINGLE,
            landscapePanelMode = WorkspacePanelMode.SINGLE,
        ),
        focusedWorkspace = namedId,
        selectedWorkspaces = mapOf(0 to namedId),
        visiblePaneSelections = mapOf(0 to namedId),
        isUpgraded = true,
        swipeGesturesEnabled = false,
        onDemandWorkspaceCreation = false,
    )

    private val pageManager = mockk<WorkspacePageManager>(relaxed = true).apply {
        every { state } returns MutableStateFlow(WorkspacePageManager.State())
    }

    private val vm = mockk<WorkspacesViewModel>(relaxed = true).apply {
        every { errorEvents } returns SingleEventFlow()
        every { navEvents } returns SingleEventFlow()
        every { shareIntentEvent } returns SingleEventFlow()
        every { bannerStates } returns MutableStateFlow(emptyMap())
        every { showClearSessionConfirmation } returns MutableStateFlow(false)
        every { managerDialogs } returns MutableStateFlow(emptyList())
        every { closedFeedback } returns MutableStateFlow<ClosedWorkspaceFeedback?>(null)
        every { workspacePageManager } returns pageManager
        every { pageHosts } returns mapOf(Workspace.Type.EXPLORER to pageHost)
        every { scrollPositions } returns WorkspaceScrollPositions()
        every { barCollapseStates } returns WorkspaceBarCollapseStates()
        every { pagerVisibility } returns WorkspaceVisibilityTracker()
        every { state } returns MutableStateFlow(screenState)
    }

    private val buttonVm = mockk<WorkspaceButtonViewModel>(relaxed = true).apply {
        every { errorEvents } returns SingleEventFlow()
        every { navEvents } returns SingleEventFlow()
        every { state } returns emptyFlow()
    }

    private val managerVm = mockk<WorkspaceManagerViewModel>(relaxed = true).apply {
        every { errorEvents } returns SingleEventFlow()
        every { navEvents } returns SingleEventFlow()
        every { state } returns emptyFlow()
    }

    @Test
    fun `the screen host publishes the resolved workspace titles to the pages it hosts`() {
        composeTestRule.setContent {
            PreviewWrapper {
                WorkspacesScreenHost(vm = vm, workspaceButtonVm = buttonVm, managerVm = managerVm)
            }
        }
        composeTestRule.waitForIdle()

        (pageHost.compositions > 0) shouldBe true
        // Every open workspace, named the way the tab strip names it - not just the hosted one, and
        // not an empty registry, which would read as "all of them are closed".
        pageHost.seen shouldBe infos.associate { it.id to it.tabLabel.get(context) }
    }
}
