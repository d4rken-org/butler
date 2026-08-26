package eu.darken.butler.workspace.ui.workspaces

import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.ui.LocalWorkspacePageHosts
import eu.darken.butler.workspace.ui.WorkspacePageHostEntry
import eu.darken.butler.workspace.ui.dialogs.PaneBoundAlertDialog
import eu.darken.butler.workspace.ui.dialogs.PaneBoundAlertDialogDefaults
import eu.darken.butler.workspace.ui.manager.WorkspaceDesign
import io.kotest.matchers.shouldBe
import org.junit.Test
import testhelpers.ComposeTest

/**
 * A pane touching a side navigation bar or a display cutout insets its *content*, never its scrims
 * and pointer barriers. Inset those and the strip beside the bar stays undimmed, stays touchable,
 * and falls outside the pane's press observer — the dialog stops being modal across the full pane.
 *
 * This drives the real [WorkspacePane] rather than the layer host on its own, because the mistake
 * this guards against was made at the call site both times it happened: padding handed to the pane
 * host instead of to the content inside it.
 *
 * The environment supplies no window insets of its own, so a fixed one is dispatched to the compose
 * view; without it every bound is identical and nothing can be told apart.
 */
class WorkspacePaneInsetBoundsTest : ComposeTest() {

    private val paneEdges = WorkspaceDesign.PaneEdges(
        touchesTop = false,
        touchesBottom = false,
        touchesStart = true,
        touchesEnd = false,
    )

    private val design = WorkspaceDesign(paneEdges = paneEdges)

    private val paneInfo = WorkspacePaneInfo(
        id = Workspace.Id(),
        type = Workspace.Type.EXPLORER,
        lifecycleState = Workspace.LifecycleState.Ready,
        title = "Test".toCaString(),
    )

    /** Stands in for a real page: a tagged page body plus one pane-bound dialog in the overlay slot. */
    private object FakePageHost : WorkspacePageHostEntry {
        @Composable
        override fun Content(id: Workspace.Id, design: WorkspaceDesign) {
            Box(modifier = Modifier.fillMaxSize().testTag(CONTENT_TAG))
        }

        @Composable
        override fun Overlays(id: Workspace.Id, design: WorkspaceDesign) {
            PaneBoundAlertDialog(
                onDismissRequest = {},
                title = { Text("Title") },
                confirmButton = { TextButton(onClick = {}) { Text("OK") } },
            )
        }
    }

    @Test
    fun `the pane host and dialog scrim span the full pane while the content is inset`() {
        var view: View? = null

        composeTestRule.setContent {
            view = LocalView.current
            // No theme wrapper on purpose: Material's defaults are enough for the geometry under
            // test, and this test shares a JVM with the rest of the module's suite.
            run {
                CompositionLocalProvider(
                    LocalWorkspacePageHosts provides mapOf(Workspace.Type.EXPLORER to FakePageHost),
                ) {
                    Box(
                        modifier = Modifier
                            .size(width = 300.dp, height = 500.dp)
                            .testTag(PANE_AREA_TAG),
                    ) {
                        WorkspacePane(
                            modifier = Modifier.fillMaxSize().testTag(PANE_HOST_TAG),
                            info = paneInfo,
                            design = design,
                            paneFocused = true,
                            activeWorkspaceId = paneInfo.id,
                            onRequestPaneFocus = {},
                            managerDialogStates = emptyMap(),
                            onScreenAction = {},
                            bannerStates = emptyMap(),
                            onDismissBanner = {},
                            onShareError = { _, _ -> },
                            onCloseWorkspace = {},
                            onResumeWorkspace = {},
                            paneEdges = paneEdges,
                        )
                    }
                }
            }
        }

        composeTestRule.runOnUiThread {
            val insets = WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.systemBars(), Insets.of(SIDE_INSET_PX, 0, 0, 0))
                .build()
            ViewCompat.dispatchApplyWindowInsets(view!!, insets)
        }
        composeTestRule.waitForIdle()

        val paneArea = composeTestRule.onNodeWithTag(PANE_AREA_TAG).getUnclippedBoundsInRoot()
        val paneHost = composeTestRule.onNodeWithTag(PANE_HOST_TAG).getUnclippedBoundsInRoot()
        val scrim = composeTestRule.onNodeWithTag(PaneBoundAlertDialogDefaults.SCRIM_TEST_TAG)
            .getUnclippedBoundsInRoot()
        val content = composeTestRule.onNodeWithTag(CONTENT_TAG).getUnclippedBoundsInRoot()
        val surface = composeTestRule.onNodeWithTag(PaneBoundAlertDialogDefaults.SURFACE_TEST_TAG)
            .getUnclippedBoundsInRoot()

        // The pane host keeps the full pane: it carries the layers, and with them the barriers
        paneHost.left shouldBe paneArea.left
        paneHost.right shouldBe paneArea.right

        // ...so the dialog's scrim and pointer barrier still cover the inset strip
        scrim.left shouldBe paneArea.left
        scrim.right shouldBe paneArea.right

        // ...while what the user reads is moved clear of the system bar
        (content.left > paneArea.left) shouldBe true
        (surface.left > paneArea.left) shouldBe true
    }

    companion object {
        private const val PANE_AREA_TAG = "pane.area"
        private const val PANE_HOST_TAG = "pane.host"
        private const val CONTENT_TAG = "page.content"

        /** Stands in for a side navigation bar; the exact width doesn't matter, only that it isn't 0. */
        private const val SIDE_INSET_PX = 72
    }
}
