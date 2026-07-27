package eu.darken.butler.workspace.ui.workspaces

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
import eu.darken.butler.workspace.ui.LocalWorkspacePageHosts
import eu.darken.butler.workspace.ui.WorkspacePageHostEntry
import eu.darken.butler.workspace.ui.manager.WorkspaceDesign
import eu.darken.butler.workspace.ui.modal.LocalPaneLayerState
import eu.darken.butler.workspace.ui.modal.PaneLayerState
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.Test
import testhelpers.ComposeTest

/**
 * The full-screen modal slot renders whichever workspace is currently deepest in the active chain,
 * from a single call site. Unwinding a chain therefore swaps the workspace under a subtree that
 * composition would otherwise treat as continuous.
 *
 * A page host reaches its page through one virtual call, so two workspaces of the same type occupy
 * the same slots: without an explicit identity the second inherits the first's remembered page
 * state. These tests pin that a swap means a fresh subtree, and that a plain recomposition of the
 * same workspace does not throw its state away.
 */
class WorkspaceModalContentIdentityTest : ComposeTest() {

    private val design = WorkspaceDesign()

    /**
     * Stands in for a real page. [instances] hands out a new number every time a page subtree is
     * composed afresh, so a carried-over number is proof the old subtree was reused.
     *
     * The `remember` is deliberately unkeyed, exactly as page-local state (scroll position, expanded
     * folders, in-page dialog state) is in the real pages.
     */
    private class RecordingHost : WorkspacePageHostEntry {

        val instances = mutableMapOf<Workspace.Id, Int>()
        val disposals = mutableListOf<Workspace.Id>()
        val layerStates = mutableMapOf<Workspace.Id, PaneLayerState?>()
        private var nextInstance = 0

        @Composable
        override fun Content(id: Workspace.Id, design: WorkspaceDesign) {
            instances[id] = remember { nextInstance++ }
            layerStates[id] = LocalPaneLayerState.current

            DisposableEffect(Unit) {
                onDispose { disposals += id }
            }

            Box(modifier = Modifier.fillMaxSize())
        }

        @Composable
        override fun Overlays(id: Workspace.Id, design: WorkspaceDesign) = Unit
    }

    /** A second, structurally identical host so a type swap runs a genuinely different body. */
    private class OtherRecordingHost : WorkspacePageHostEntry {

        val instances = mutableMapOf<Workspace.Id, Int>()
        val disposals = mutableListOf<Workspace.Id>()
        val layerStates = mutableMapOf<Workspace.Id, PaneLayerState?>()
        private var nextInstance = 100

        @Composable
        override fun Content(id: Workspace.Id, design: WorkspaceDesign) {
            instances[id] = remember { nextInstance++ }
            layerStates[id] = LocalPaneLayerState.current

            DisposableEffect(Unit) {
                onDispose { disposals += id }
            }

            Box(modifier = Modifier.fillMaxSize())
        }

        @Composable
        override fun Overlays(id: Workspace.Id, design: WorkspaceDesign) = Unit
    }

    private fun info(
        name: String,
        type: Workspace.Type = Workspace.Type.EXPLORER,
    ) = Workspace.Info(
        id = Workspace.Id(),
        type = type,
        title = name.toCaString(),
        callerWorkspaceId = Workspace.Id(),
    )

    @Composable
    private fun Content(host: WorkspacePageHostEntry, workspace: Workspace.Info) {
        PreviewWrapper {
            CompositionLocalProvider(
                LocalWorkspacePageHosts provides mapOf(Workspace.Type.EXPLORER to host),
            ) {
                Box(modifier = Modifier.size(width = 400.dp, height = 700.dp)) {
                    WorkspaceModalContent(workspace = workspace, design = design)
                }
            }
        }
    }

    /**
     * The case the key exists for: same type, different workspace. Nothing about the emitted slots
     * differs, so without an explicit identity the second workspace would land on the first's
     * remembered state.
     */
    @Test
    fun `swapping to another workspace of the same type builds a fresh subtree`() {
        val host = RecordingHost()
        val first = info("First")
        val second = info("Second")
        var current by mutableStateOf(first)

        composeTestRule.setContent { Content(host = host, workspace = current) }
        composeTestRule.waitForIdle()

        host.instances[first.id] shouldNotBe null

        composeTestRule.runOnIdle { current = second }
        composeTestRule.waitForIdle()

        host.instances[second.id] shouldNotBe null
        host.instances[second.id] shouldNotBe host.instances[first.id]
        host.disposals shouldBe listOf(first.id)
    }

    /**
     * Guards the other direction: the key must not make every recomposition throw page state away.
     */
    @Test
    fun `recomposing the same workspace keeps its subtree`() {
        val host = RecordingHost()
        val workspace = info("Only")
        var nudge by mutableStateOf(0)

        composeTestRule.setContent {
            nudge.toString()
            Content(host = host, workspace = workspace)
        }
        composeTestRule.waitForIdle()

        val before = host.instances[workspace.id]

        composeTestRule.runOnIdle { nudge = 1 }
        composeTestRule.waitForIdle()

        host.instances[workspace.id] shouldBe before
        host.disposals shouldBe emptyList()
    }

    /**
     * A real type swap runs a different page host's composable, which emits its own slots. Two
     * distinct host classes are required to model that: routing both types to one host instance
     * would run the same body either way and prove nothing about type changes.
     */
    @Test
    fun `swapping to another type builds a fresh subtree`() {
        val explorerHost = RecordingHost()
        val saverHost = OtherRecordingHost()
        val picker = info("Picker", type = Workspace.Type.EXPLORER)
        val saver = info("Saver", type = Workspace.Type.SAVER)
        var current by mutableStateOf(picker)

        composeTestRule.setContent {
            PreviewWrapper {
                CompositionLocalProvider(
                    LocalWorkspacePageHosts provides mapOf(
                        Workspace.Type.EXPLORER to explorerHost,
                        Workspace.Type.SAVER to saverHost,
                    ),
                ) {
                    Box(modifier = Modifier.size(width = 400.dp, height = 700.dp)) {
                        WorkspaceModalContent(workspace = current, design = design)
                    }
                }
            }
        }
        composeTestRule.waitForIdle()

        explorerHost.instances[picker.id] shouldNotBe null

        composeTestRule.runOnIdle { current = saver }
        composeTestRule.waitForIdle()

        saverHost.instances[saver.id] shouldNotBe null
        explorerHost.disposals shouldBe listOf(picker.id)
    }

    /**
     * The layer host sits above the page, so keying the content rebuilds it on every workspace
     * change - including the type changes a real unwind is made of, which previously kept one host
     * for the whole chain. Pins that each workspace gets its own layer stack and that the stack it
     * gets is empty of the previous workspace's layers, which is the property the pane path already
     * has and the one an unwind depends on.
     */
    @Test
    fun `each workspace gets its own layer stack`() {
        val explorerHost = RecordingHost()
        val saverHost = OtherRecordingHost()
        val picker = info("Picker", type = Workspace.Type.EXPLORER)
        val saver = info("Saver", type = Workspace.Type.SAVER)
        var current by mutableStateOf(picker)

        composeTestRule.setContent {
            PreviewWrapper {
                CompositionLocalProvider(
                    LocalWorkspacePageHosts provides mapOf(
                        Workspace.Type.EXPLORER to explorerHost,
                        Workspace.Type.SAVER to saverHost,
                    ),
                ) {
                    Box(modifier = Modifier.size(width = 400.dp, height = 700.dp)) {
                        WorkspaceModalContent(workspace = current, design = design)
                    }
                }
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.runOnIdle { current = saver }
        composeTestRule.waitForIdle()

        // Each page saw a layer stack, and the second workspace did not inherit the first's.
        explorerHost.layerStates[picker.id] shouldNotBe null
        saverHost.layerStates[saver.id] shouldNotBe null
        saverHost.layerStates[saver.id] shouldNotBe explorerHost.layerStates[picker.id]
    }
}
