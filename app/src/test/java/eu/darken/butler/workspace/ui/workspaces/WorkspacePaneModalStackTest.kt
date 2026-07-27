package eu.darken.butler.workspace.ui.workspaces

import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.ui.LocalWorkspaceFocused
import eu.darken.butler.workspace.ui.LocalWorkspacePageHosts
import eu.darken.butler.workspace.ui.WorkspacePageHostEntry
import eu.darken.butler.workspace.ui.manager.WorkspaceDesign
import eu.darken.butler.workspace.ui.modal.LocalLayerActive
import eu.darken.butler.workspace.ui.modal.WorkspaceBackHandler
import io.kotest.matchers.shouldBe
import org.junit.Test
import testhelpers.ComposeTest

/**
 * A pane renders a whole modal chain, not just one child, so the stack has to behave at depth 2 and
 * beyond: back belongs to the deepest modal alone, uncovering one hands control to the next, and a
 * modal that stays in the chain must not be rebuilt when a deeper one comes and goes.
 *
 * Drives the real [WorkspacePane] with stand-in page hosts — the layer host tests below it prove
 * ranks arbitrate correctly, but nothing there exercises a chain being mutated at runtime.
 */
class WorkspacePaneModalStackTest : ComposeTest() {

    private val design = WorkspaceDesign()

    private val tab = paneInfo("Tab")
    private val modalA = paneInfo("A")
    private val modalB = paneInfo("B")
    private val modalC = paneInfo("C")

    /**
     * Stands in for every page in the pane: records what its workspace was told about itself and
     * registers the workspace-scoped back handler a real page would.
     */
    private class RecordingHost : WorkspacePageHostEntry {

        val backReceipts = mutableListOf<Workspace.Id>()
        val disposals = mutableListOf<Workspace.Id>()
        val focused = mutableMapOf<Workspace.Id, Boolean>()
        val layerActive = mutableMapOf<Workspace.Id, Boolean>()

        /** Changes only when the subtree for an id is disposed and composed afresh. */
        val instances = mutableMapOf<Workspace.Id, Int>()
        private var nextInstance = 0

        @Composable
        override fun Content(id: Workspace.Id, design: WorkspaceDesign) {
            focused[id] = LocalWorkspaceFocused.current
            layerActive[id] = LocalLayerActive.current
            instances[id] = remember { nextInstance++ }

            DisposableEffect(id) {
                onDispose { disposals += id }
            }

            WorkspaceBackHandler { backReceipts += id }

            Box(modifier = Modifier.fillMaxSize())
        }

        @Composable
        override fun Overlays(id: Workspace.Id, design: WorkspaceDesign) = Unit
    }

    @Composable
    private fun Pane(
        host: WorkspacePageHostEntry,
        childModals: List<WorkspacePaneInfo>,
        activeWorkspaceId: Workspace.Id?,
    ) {
        PreviewWrapper {
            CompositionLocalProvider(
                LocalWorkspacePageHosts provides mapOf(Workspace.Type.EXPLORER to host),
            ) {
                Box(modifier = Modifier.size(width = 400.dp, height = 700.dp)) {
                    WorkspacePane(
                        modifier = Modifier.fillMaxSize(),
                        info = tab,
                        design = design,
                        paneFocused = true,
                        onRequestPaneFocus = {},
                        managerDialogStates = emptyMap(),
                        onDismissManagerDialog = {},
                        onConfirmManagerDialog = {},
                        bannerStates = emptyMap(),
                        onDismissBanner = {},
                        onShareError = { _, _ -> },
                        onCloseWorkspace = {},
                        onResumeWorkspace = {},
                        childModals = childModals,
                        activeWorkspaceId = activeWorkspaceId,
                    )
                }
            }
        }
    }

    @Test
    fun `only the deepest modal of the chain receives back`() {
        val host = RecordingHost()
        var dispatcher: OnBackPressedDispatcher? = null

        composeTestRule.setContent {
            dispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
            Pane(
                host = host,
                childModals = listOf(modalA, modalB),
                activeWorkspaceId = modalB.id,
            )
        }
        composeTestRule.waitForIdle()

        composeTestRule.runOnIdle { dispatcher!!.onBackPressed() }
        composeTestRule.waitForIdle()

        host.backReceipts shouldBe listOf(modalB.id)
    }

    @Test
    fun `uncovering a modal hands the next back press to it`() {
        val host = RecordingHost()
        var chain by mutableStateOf(listOf(modalA, modalB))
        var dispatcher: OnBackPressedDispatcher? = null

        composeTestRule.setContent {
            dispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
            Pane(
                host = host,
                childModals = chain,
                activeWorkspaceId = chain.lastOrNull()?.id ?: tab.id,
            )
        }
        composeTestRule.waitForIdle()

        composeTestRule.runOnIdle { dispatcher!!.onBackPressed() }
        composeTestRule.waitForIdle()

        // The deepest modal answered, so it is the one that closes
        composeTestRule.runOnIdle { chain = listOf(modalA) }
        composeTestRule.waitForIdle()

        composeTestRule.runOnIdle { dispatcher!!.onBackPressed() }
        composeTestRule.waitForIdle()

        host.backReceipts shouldBe listOf(modalB.id, modalA.id)
    }

    @Test
    fun `focus and layer activeness belong to the visible top workspace only`() {
        val host = RecordingHost()

        composeTestRule.setContent {
            Pane(
                host = host,
                childModals = listOf(modalA, modalB),
                activeWorkspaceId = modalB.id,
            )
        }
        composeTestRule.waitForIdle()

        host.focused[tab.id] shouldBe false
        host.focused[modalA.id] shouldBe false
        host.focused[modalB.id] shouldBe true

        host.layerActive[tab.id] shouldBe false
        host.layerActive[modalA.id] shouldBe false
        host.layerActive[modalB.id] shouldBe true
    }

    @Test
    fun `a modal that stays in the chain keeps its state while a deeper one comes and goes`() {
        val host = RecordingHost()
        var chain by mutableStateOf(listOf(modalA))

        composeTestRule.setContent {
            Pane(
                host = host,
                childModals = chain,
                activeWorkspaceId = chain.last().id,
            )
        }
        composeTestRule.waitForIdle()
        val original = host.instances[modalA.id]

        composeTestRule.runOnIdle { chain = listOf(modalA, modalB) }
        composeTestRule.waitForIdle()
        host.instances[modalA.id] shouldBe original

        composeTestRule.runOnIdle { chain = listOf(modalA) }
        composeTestRule.waitForIdle()

        host.instances[modalA.id] shouldBe original
        host.disposals shouldBe listOf(modalB.id)
    }

    @Test
    fun `switching to a sibling branch disposes the replaced modal instead of reusing it`() {
        val host = RecordingHost()
        var chain by mutableStateOf(listOf(modalA, modalB))

        composeTestRule.setContent {
            Pane(
                host = host,
                childModals = chain,
                activeWorkspaceId = chain.last().id,
            )
        }
        composeTestRule.waitForIdle()
        val replaced = host.instances[modalB.id]

        composeTestRule.runOnIdle { chain = listOf(modalA, modalC) }
        composeTestRule.waitForIdle()

        host.disposals shouldBe listOf(modalB.id)
        (host.instances[modalC.id] != replaced) shouldBe true
        host.focused[modalC.id] shouldBe true
    }

    companion object {
        private fun paneInfo(name: String) = WorkspacePaneInfo(
            id = Workspace.Id(),
            type = Workspace.Type.EXPLORER,
            lifecycleState = Workspace.LifecycleState.Ready,
            title = name.toCaString(),
        )
    }
}
