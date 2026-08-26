package eu.darken.butler.workspace.ui.workspaces

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.ui.LocalWorkspacePageHosts
import eu.darken.butler.workspace.ui.WorkspacePageHostEntry
import eu.darken.butler.workspace.ui.dialogs.ManagerDialog
import eu.darken.butler.workspace.ui.dialogs.ManagerDialogAction
import eu.darken.butler.workspace.ui.manager.WorkspaceDesign
import io.kotest.matchers.shouldBe
import org.junit.Test
import org.robolectric.annotation.Config
import testhelpers.ComposeTest

/**
 * The dialog layer reports through [ManagerDialogAction]; the pane is where that becomes a
 * [WorkspaceScreenAction] the ViewModel can act on.
 */
@Config(qualifiers = "w400dp-h800dp")
class WorkspacePaneManagerDialogTest : ComposeTest() {

    private val design = WorkspaceDesign()
    private val hostId = Workspace.Id()
    private val closingId = Workspace.Id()

    private class EmptyPageHost : WorkspacePageHostEntry {
        @Composable
        override fun Content(id: Workspace.Id, design: WorkspaceDesign) {
            Box(modifier = Modifier.fillMaxSize().testTag("page"))
        }

        @Composable
        override fun Overlays(id: Workspace.Id, design: WorkspaceDesign) {
        }
    }

    private fun paneInfo(id: Workspace.Id) = WorkspacePaneInfo(
        id = id,
        type = Workspace.Type.EXPLORER,
        lifecycleState = Workspace.LifecycleState.Ready,
        title = "Explorer".toCaString(),
    )

    private fun compose(closing: Workspace.Id): MutableList<WorkspaceScreenAction> {
        val actions = mutableListOf<WorkspaceScreenAction>()
        val info = paneInfo(hostId)
        val dialog = ManagerDialog.WorkspaceTargeted.CloseConfirmation(
            id = "c1",
            targetWorkspaceId = hostId,
            closingWorkspaceId = closing,
            workspaceTitle = "notes.txt".toCaString(),
            hasUnsavedChanges = true,
        )

        composeTestRule.setContent {
            PreviewWrapper {
                CompositionLocalProvider(
                    LocalWorkspacePageHosts provides mapOf(Workspace.Type.EXPLORER to EmptyPageHost()),
                ) {
                    Box(modifier = Modifier.size(width = 400.dp, height = 800.dp)) {
                        WorkspacePane(
                            modifier = Modifier.fillMaxSize(),
                            info = info,
                            design = design,
                            paneFocused = true,
                            activeWorkspaceId = info.id,
                            onRequestPaneFocus = {},
                            managerDialogStates = mapOf(hostId to dialog),
                            onScreenAction = { actions += it },
                            bannerStates = emptyMap(),
                            onDismissBanner = {},
                            onShareError = { _, _ -> },
                            onCloseWorkspace = {},
                            onResumeWorkspace = {},
                        )
                    }
                }
            }
        }
        return actions
    }

    @Test
    fun `confirming reports a resolve for that confirmation`() {
        val actions = compose(closing = hostId)

        composeTestRule.onNodeWithText("Discard").performClick()

        actions shouldBe listOf(
            WorkspaceScreenAction.HandleDialog(ManagerDialogAction.Resolve("c1", confirmed = true)),
        )
    }

    @Test
    fun `cancelling reports a resolve for that confirmation`() {
        val actions = compose(closing = hostId)

        composeTestRule.onNodeWithText("Cancel").performClick()

        actions shouldBe listOf(
            WorkspaceScreenAction.HandleDialog(ManagerDialogAction.Resolve("c1", confirmed = false)),
        )
    }

    @Test
    fun `a dialog about its own pane offers no jump`() {
        compose(closing = hostId)

        composeTestRule.onNodeWithText("Go to tab").assertDoesNotExist()
    }

    @Test
    fun `a dialog about another tab jumps to it from this pane`() {
        val actions = compose(closing = closingId)

        composeTestRule.onNodeWithText("Go to tab").performClick()

        // The source is the host pane, so the tab lands where the user is already looking.
        actions shouldBe listOf(
            WorkspaceScreenAction.HandleDialog(
                ManagerDialogAction.CancelAndGoToWorkspace(
                    confirmationId = "c1",
                    workspaceId = closingId,
                    sourceWorkspaceId = hostId,
                ),
            ),
        )
    }
}
