package eu.darken.butler.workspace.ui.workspaces

import android.view.View
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.error.ErrorEventHandler
import eu.darken.butler.common.error.ErrorEventSource
import eu.darken.butler.common.error.HasLocalizedError
import eu.darken.butler.common.error.LocalizedError
import eu.darken.butler.common.error.LocalizedErrorContext
import eu.darken.butler.common.flow.SingleEventFlow
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
 * An error raised by a page has to end up as a full-pane modal on top of everything that page has
 * open, which is only true if its handler lives in the overlay slot.
 *
 * This drives the real [WorkspacePane], with side insets applied and a competing overlay already
 * open. Rendering the error dialog directly inside a full-size layer host would pass even with the
 * handler back in the content slot, so it would prove nothing about either property.
 */
class WorkspacePaneErrorDialogTest : ComposeTest() {

    private val paneEdges = WorkspaceDesign.PaneEdges(
        touchesTop = false,
        touchesBottom = false,
        touchesStart = true,
        touchesEnd = false,
    )

    private val design = WorkspaceDesign(paneEdges = paneEdges)

    private class TestError : RuntimeException("boom"), HasLocalizedError {
        override fun getLocalizedError(context: LocalizedErrorContext) = LocalizedError(
            throwable = this,
            label = ERROR_LABEL.toCaString(),
            description = "Something went wrong".toCaString(),
        )
    }

    /** Stands in for a page: a tagged body, one overlay of its own, and a real error handler. */
    private class FakePageHost(
        private val errorSource: ErrorEventSource,
        private val overlayOpen: () -> Boolean,
    ) : WorkspacePageHostEntry {

        @Composable
        override fun Content(id: Workspace.Id, design: WorkspaceDesign) {
            Box(modifier = Modifier.fillMaxSize().testTag(CONTENT_TAG))
        }

        @Composable
        override fun Overlays(id: Workspace.Id, design: WorkspaceDesign) {
            if (overlayOpen()) {
                PaneBoundAlertDialog(
                    onDismissRequest = {},
                    title = { Text(COMPETING_OVERLAY_LABEL) },
                    confirmButton = { TextButton(onClick = {}) { Text("OK") } },
                )
            }
            // Mirrors the real hosts: last, so an error lands above the page's own dialogs
            ErrorEventHandler(errorSource)
        }
    }

    private fun paneInfo(lifecycleState: Workspace.LifecycleState) = WorkspacePaneInfo(
        id = Workspace.Id(),
        type = Workspace.Type.EXPLORER,
        lifecycleState = lifecycleState,
        title = "Test".toCaString(),
    )

    @Composable
    private fun Pane(info: WorkspacePaneInfo, host: WorkspacePageHostEntry) {
        CompositionLocalProvider(
            LocalWorkspacePageHosts provides mapOf(Workspace.Type.EXPLORER to host),
        ) {
            Box(
                modifier = Modifier
                    .size(width = 400.dp, height = 700.dp)
                    .testTag(PANE_AREA_TAG),
            ) {
                WorkspacePane(
                    modifier = Modifier.fillMaxSize(),
                    info = info,
                    design = design,
                    paneFocused = true,
                    activeWorkspaceId = info.id,
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

    private fun dispatchSideInset(view: View) {
        composeTestRule.runOnUiThread {
            val insets = WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.systemBars(), Insets.of(SIDE_INSET_PX, 0, 0, 0))
                .build()
            ViewCompat.dispatchApplyWindowInsets(view, insets)
        }
        composeTestRule.waitForIdle()
    }

    @Test
    fun `an error raised by the page spans the full pane and covers its other overlays`() {
        val errors = SingleEventFlow<Throwable>()
        val source = object : ErrorEventSource {
            override val errorEvents = errors
        }
        val host = FakePageHost(errorSource = source, overlayOpen = { true })
        val info = paneInfo(Workspace.LifecycleState.Ready)
        var view: View? = null
        var dispatcher: OnBackPressedDispatcher? = null

        composeTestRule.setContent {
            view = LocalView.current
            dispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
            Pane(info = info, host = host)
        }
        dispatchSideInset(view!!)

        composeTestRule.onNodeWithText(COMPETING_OVERLAY_LABEL).assertExists()

        composeTestRule.runOnIdle { errors.tryEmit(TestError()) }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText(ERROR_LABEL).assertExists()

        val paneArea = composeTestRule.onNodeWithTag(PANE_AREA_TAG).getUnclippedBoundsInRoot()
        val content = composeTestRule.onNodeWithTag(CONTENT_TAG).getUnclippedBoundsInRoot()
        val scrimNodes = composeTestRule.onAllNodesWithTag(PaneBoundAlertDialogDefaults.SCRIM_TEST_TAG)

        // Both dialogs are up, and each scrim spans the whole pane rather than the inset content
        scrimNodes.fetchSemanticsNodes().size shouldBe 2
        repeat(2) { index ->
            val bounds = scrimNodes[index].getUnclippedBoundsInRoot()
            bounds.left shouldBe paneArea.left
            bounds.right shouldBe paneArea.right
        }

        // ...while the page content underneath stays clear of the system bar
        (content.left > paneArea.left) shouldBe true

        // The error is the top layer, so back belongs to it: it closes and hands the page's own
        // dialog back rather than dismissing that one from underneath it
        composeTestRule.runOnIdle { dispatcher!!.onBackPressed() }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText(ERROR_LABEL).assertDoesNotExist()
        scrimNodes.fetchSemanticsNodes().size shouldBe 1
    }

    /**
     * `Overlays` used to be gated on `Ready`, so an error raised while a workspace was still coming
     * up had nowhere to render. The event is buffered, but only reaches the user if the slot is
     * composed at all.
     */
    @Test
    fun `an error raised before the workspace is ready still reaches the user`() {
        val errors = SingleEventFlow<Throwable>()
        val source = object : ErrorEventSource {
            override val errorEvents = errors
        }
        val host = FakePageHost(errorSource = source, overlayOpen = { false })
        var lifecycleState by mutableStateOf<Workspace.LifecycleState>(
            Workspace.LifecycleState.Initializing,
        )
        val id = Workspace.Id()

        composeTestRule.setContent {
            Pane(
                info = WorkspacePaneInfo(
                    id = id,
                    type = Workspace.Type.EXPLORER,
                    lifecycleState = lifecycleState,
                    title = "Test".toCaString(),
                ),
                host = host,
            )
        }

        composeTestRule.runOnIdle { errors.tryEmit(TestError()) }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText(ERROR_LABEL).assertExists()

        // ...and it survives the workspace finishing its startup
        composeTestRule.runOnIdle { lifecycleState = Workspace.LifecycleState.Ready }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText(ERROR_LABEL).assertExists()
    }

    companion object {
        private const val PANE_AREA_TAG = "pane.area"
        private const val CONTENT_TAG = "page.content"
        private const val ERROR_LABEL = "Access denied"
        private const val COMPETING_OVERLAY_LABEL = "Sort options"

        /** Stands in for a side navigation bar; only that it isn't 0 matters. */
        private const val SIDE_INSET_PX = 72
    }
}
