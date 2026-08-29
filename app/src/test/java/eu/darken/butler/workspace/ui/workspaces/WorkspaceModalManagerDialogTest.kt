package eu.darken.butler.workspace.ui.workspaces

import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.ui.LocalWorkspacePageHosts
import eu.darken.butler.workspace.ui.WorkspacePageHostEntry
import eu.darken.butler.workspace.ui.dialogs.ManagerDialog
import eu.darken.butler.workspace.ui.dialogs.ManagerDialogAction
import eu.darken.butler.workspace.ui.dialogs.PaneBoundAlertDialogDefaults
import eu.darken.butler.workspace.ui.manager.WorkspaceDesign
import eu.darken.butler.workspace.ui.modal.LocalLayerActive
import eu.darken.butler.workspace.ui.modal.LocalPaneLayerState
import eu.darken.butler.workspace.ui.modal.PaneLayer
import eu.darken.butler.workspace.ui.modal.PaneLayerState
import eu.darken.butler.workspace.ui.modal.WorkspaceBackHandler
import io.kotest.matchers.shouldBe
import org.junit.Test
import org.robolectric.annotation.Config
import testhelpers.ComposeTest
import testhelpers.TestApplication

/**
 * A full-screen modal owns its own window and layer stack, so a confirmation anchored to it can only
 * be asked there: composed anywhere else it would render behind the window it belongs to.
 *
 * Drives [WorkspaceModalContent] rather than [WorkspaceModalDialog] - the Dialog wrapper only
 * reconciles a platform window with the Activity's edge-to-edge setup, and everything under test
 * lives in the content.
 */
@Config(application = TestApplication::class, sdk = [34], qualifiers = "w400dp-h800dp")
class WorkspaceModalManagerDialogTest : ComposeTest() {

    private val design = WorkspaceDesign()

    /** Stands in for the modal's page, including the overlay slot and back handler a real one has. */
    private class RecordingHost : WorkspacePageHostEntry {

        val backReceipts = mutableListOf<Workspace.Id>()
        val overlayLayerActive = mutableMapOf<Workspace.Id, Boolean>()
        val layerStates = mutableMapOf<Workspace.Id, PaneLayerState?>()

        @Composable
        override fun Content(id: Workspace.Id, design: WorkspaceDesign) {
            layerStates[id] = LocalPaneLayerState.current
            WorkspaceBackHandler { backReceipts += id }
            Box(modifier = Modifier.fillMaxSize().testTag(PAGE_TAG))
        }

        @Composable
        override fun Overlays(id: Workspace.Id, design: WorkspaceDesign) {
            PaneLayer(modifier = Modifier.fillMaxSize(), modal = false) {
                overlayLayerActive[id] = LocalLayerActive.current
            }
        }
    }

    private fun modalInfo(
        lifecycleState: Workspace.LifecycleState = Workspace.LifecycleState.Ready,
    ) = Workspace.Info(
        id = Workspace.Id(),
        type = Workspace.Type.EXPLORER,
        title = "Select Folder".toCaString(),
        lifecycleState = lifecycleState,
        callerWorkspaceId = Workspace.Id(),
    )

    private fun confirmation(anchor: Workspace.Id) = ManagerDialog.WorkspaceTargeted.CloseConfirmation(
        id = "c1",
        targetWorkspaceId = anchor,
        closingWorkspaceId = Workspace.Id(),
        workspaceTitle = TITLE.toCaString(),
        hasUnsavedChanges = true,
    )

    @Composable
    private fun Content(
        host: WorkspacePageHostEntry,
        workspace: Workspace.Info,
        dialog: ManagerDialog.WorkspaceTargeted?,
        onScreenAction: (WorkspaceScreenAction) -> Unit = {},
    ) {
        PreviewWrapper {
            CompositionLocalProvider(
                LocalWorkspacePageHosts provides mapOf(
                    Workspace.Type.EXPLORER to host,
                    Workspace.Type.SAVER to host,
                ),
            ) {
                WorkspaceModalContent(
                    workspace = workspace,
                    design = design,
                    managerDialog = dialog,
                    onScreenAction = onScreenAction,
                )
            }
        }
    }

    @Test
    fun `the confirmation is asked inside the modal, above its page`() {
        val host = RecordingHost()
        val workspace = modalInfo()

        composeTestRule.setContent {
            Content(host = host, workspace = workspace, dialog = confirmation(workspace.id))
        }
        composeTestRule.waitForIdle()

        // Pane-bound, not a second platform window: the modal already owns the window, and the
        // dialog's scrim has to cover the page inside it.
        composeTestRule.onNodeWithTag(PaneBoundAlertDialogDefaults.SURFACE_TEST_TAG).assertIsDisplayed()
        composeTestRule.onNodeWithText(TITLE, substring = true).assertIsDisplayed()
        // Manager rank sits above the overlay tier, so the page's overlays go inactive under it.
        host.overlayLayerActive[workspace.id] shouldBe false
    }

    @Test
    fun `back answers the confirmation instead of reaching the page`() {
        val host = RecordingHost()
        val workspace = modalInfo()
        val actions = mutableListOf<WorkspaceScreenAction>()
        var dispatcher: OnBackPressedDispatcher? = null

        composeTestRule.setContent {
            dispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
            Content(
                host = host,
                workspace = workspace,
                dialog = confirmation(workspace.id),
                onScreenAction = { actions += it },
            )
        }
        composeTestRule.waitForIdle()

        composeTestRule.runOnIdle { dispatcher!!.onBackPressed() }
        composeTestRule.waitForIdle()

        actions shouldBe listOf(
            WorkspaceScreenAction.HandleDialog(ManagerDialogAction.Resolve("c1", confirmed = false)),
        )
        host.backReceipts shouldBe emptyList()
    }

    @Test
    fun `a paused modal can still be asked`() {
        val host = RecordingHost()
        val workspace = modalInfo(lifecycleState = Workspace.LifecycleState.Paused())

        composeTestRule.setContent {
            Content(host = host, workspace = workspace, dialog = confirmation(workspace.id))
        }
        composeTestRule.waitForIdle()

        // Nothing else can answer for a paused workspace, so the placeholder still carries its
        // confirmation.
        composeTestRule.onNodeWithTag(PaneBoundAlertDialogDefaults.SURFACE_TEST_TAG).assertIsDisplayed()
    }

    @Test
    fun `swapping the workspace rebuilds the confirmation with the new layer stack`() {
        val host = RecordingHost()
        val first = modalInfo()
        val second = modalInfo().copy(type = Workspace.Type.SAVER)
        var current by mutableStateOf(first)

        composeTestRule.setContent {
            Content(host = host, workspace = current, dialog = confirmation(current.id))
        }
        composeTestRule.waitForIdle()

        val firstStack = host.layerStates[first.id]!!

        composeTestRule.runOnIdle { current = second }
        composeTestRule.waitForIdle()

        // Unwinding a chain swaps the workspace under this one call site. The dialog belongs to the
        // stack of the workspace it is anchored to, so that stack going away has to take it along.
        firstStack.layerCount shouldBe 0
        host.overlayLayerActive[second.id] shouldBe false
        composeTestRule.onNodeWithTag(PaneBoundAlertDialogDefaults.SURFACE_TEST_TAG).assertIsDisplayed()
    }

    companion object {
        private const val TITLE = "notes.txt"
        private const val PAGE_TAG = "modal-page-content"
    }
}
