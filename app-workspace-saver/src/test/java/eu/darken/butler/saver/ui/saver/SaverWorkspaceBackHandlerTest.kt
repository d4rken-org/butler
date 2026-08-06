package eu.darken.butler.saver.ui.saver

import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.ui.manager.WorkspaceDesign
import eu.darken.butler.workspace.ui.modal.LocalWorkspaceIsPaneModal
import eu.darken.butler.workspace.ui.modal.PaneLayer
import eu.darken.butler.workspace.ui.modal.PaneLayerHost
import eu.darken.butler.workspace.ui.modal.PaneLayerRank
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.Test
import testhelpers.ComposeTest

/**
 * A pane-local saver stacks inside its caller's pane at any pane count, with no dialog window to
 * supply back handling, so the page has to own it — but only there. A saver asking for FULL_SCREEN
 * renders inside a real Dialog that already consumes back, and the ACTION_SEND share entry point is
 * a normal tab; both must leave back alone.
 */
class SaverWorkspaceBackHandlerTest : ComposeTest() {

    private fun mockVm() = mockk<SaverWorkspaceViewModel>(relaxed = true).apply {
        every { conflictUiState } returns MutableStateFlow(SaverWorkspaceViewModel.ConflictUiState())
    }

    private fun setPage(
        vm: SaverWorkspaceViewModel,
        stateSource: Flow<SaverWorkspaceViewModel.State>,
        paneModal: Boolean = false,
    ): () -> Unit {
        var dispatcher: OnBackPressedDispatcher? = null

        // The non-modal header composes WorkspaceButton, whose mascot is an infinite Lottie
        // animation - under Robolectric that floods every frame, so drive the clock manually.
        composeTestRule.mainClock.autoAdvance = false

        composeTestRule.setContent {
            dispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
            PreviewWrapper {
                PaneLayerHost(modifier = Modifier.fillMaxSize(), paneFocused = true) {
                    PaneLayer(
                        modifier = Modifier.fillMaxSize(),
                        rank = PaneLayerRank.CONTENT,
                        modal = false,
                    ) {
                        CompositionLocalProvider(LocalWorkspaceIsPaneModal provides paneModal) {
                            SaverWorkspacePage(
                                workspaceId = Workspace.Id(),
                                design = WorkspaceDesign(),
                                stateSource = stateSource,
                                vm = vm,
                            )
                        }
                    }
                }
            }
        }

        // Let the state flow reach composition before back is dispatched.
        composeTestRule.mainClock.advanceTimeBy(1_000)

        return { composeTestRule.runOnIdle { dispatcher!!.onBackPressed() } }
    }

    @Test
    fun `pane modal saver closes on back`() {
        val vm = mockVm()
        val pressBack = setPage(vm, flowOf(SaverWorkspaceViewModel.State(isModal = true)), paneModal = true)

        pressBack()

        composeTestRule.runOnIdle { verify(exactly = 1) { vm.onClose() } }
    }

    /**
     * A saver asking for FULL_SCREEN presentation is hosted by a real Dialog, which handles back via
     * its own dismiss request. The page must stay out of it - a second consumer closes the workspace
     * twice and the second close lands on an already-removed workspace, throwing tab navigation off.
     */
    @Test
    fun `full screen saver leaves back to the dialog`() {
        val vm = mockVm()
        val pressBack = setPage(vm, flowOf(SaverWorkspaceViewModel.State(isModal = true)), paneModal = false)

        pressBack()

        composeTestRule.runOnIdle { verify(exactly = 0) { vm.onClose() } }
    }

    @Test
    fun `share intent saver ignores back`() {
        val vm = mockVm()
        val pressBack = setPage(vm, flowOf(SaverWorkspaceViewModel.State(isModal = false)))

        pressBack()

        composeTestRule.runOnIdle { verify(exactly = 0) { vm.onClose() } }
    }

    /**
     * The page is precomposed while the workspace still initializes, so `collectAsState` sits on its
     * default value (isModal=false) for the first frames. Back must not escape the modal during that
     * window - the layer above knows the answer synchronously and supplies it.
     */
    @Test
    fun `pane modal saver closes on back before its state emits`() {
        val vm = mockVm()
        val pressBack = setPage(vm, MutableSharedFlow(), paneModal = true)

        pressBack()

        composeTestRule.runOnIdle { verify(exactly = 1) { vm.onClose() } }
    }

    @Test
    fun `share intent saver ignores back before its state emits`() {
        val vm = mockVm()
        val pressBack = setPage(vm, MutableSharedFlow(), paneModal = false)

        pressBack()

        composeTestRule.runOnIdle { verify(exactly = 0) { vm.onClose() } }
    }
}
