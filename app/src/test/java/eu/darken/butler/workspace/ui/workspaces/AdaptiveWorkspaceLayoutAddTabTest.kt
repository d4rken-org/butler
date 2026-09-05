package eu.darken.butler.workspace.ui.workspaces

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import eu.darken.butler.R
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.WorkspaceAction
import eu.darken.butler.workspace.ui.LocalWorkspacePageHosts
import eu.darken.butler.workspace.ui.WorkspacePageHostEntry
import eu.darken.butler.workspace.ui.manager.FakeWorkspaceButtonProvider
import eu.darken.butler.workspace.ui.manager.LocalWorkspaceButtonProvider
import eu.darken.butler.workspace.ui.manager.WorkspaceButtonProvider
import eu.darken.butler.workspace.ui.manager.WorkspaceDesign
import eu.darken.butler.workspace.ui.workspaces.adaptive.DividerPositions
import io.kotest.matchers.shouldBe
import org.junit.Test
import testhelpers.ComposeTest

/**
 * The rail's "+" and the Butler menu's "New tab" have to issue the same request: the Templates
 * picker, created from the focused tab, focused, and allowed to recover from the tab limit. Nothing
 * pinned the "+" before, and the rail file is rewritten often enough for that wiring to be lost
 * without any other test noticing.
 */
class AdaptiveWorkspaceLayoutAddTabTest : ComposeTest() {

    private val tab = Workspace.Info(
        id = Workspace.Id(),
        type = Workspace.Type.EXPLORER,
        title = "Explorer".toCaString(),
        lifecycleState = Workspace.LifecycleState.Ready,
    )

    private class RecordingButtonProvider : WorkspaceButtonProvider by FakeWorkspaceButtonProvider() {
        var templatesRequests = 0
        val actions = mutableListOf<WorkspaceAction>()

        override fun createTemplatesWorkspace() {
            templatesRequests++
        }

        override fun executeWorkspaceAction(action: WorkspaceAction) {
            actions += action
        }
    }

    /** Stands in for the real page, which would instantiate Hilt ViewModels. */
    private object StubPageHost : WorkspacePageHostEntry {

        @Composable
        override fun Content(id: Workspace.Id, design: WorkspaceDesign) {
        }

        @Composable
        override fun Overlays(id: Workspace.Id, design: WorkspaceDesign) {
        }
    }

    private val addTabDescription: String
        get() = ApplicationProvider.getApplicationContext<Context>()
            .getString(R.string.workspace_add_tab_description)

    private fun setLayout(provider: RecordingButtonProvider) {
        val pageHosts = mapOf<Workspace.Type, WorkspacePageHostEntry>(
            Workspace.Type.EXPLORER to StubPageHost,
        )
        composeTestRule.setContent {
            PreviewWrapper {
                CompositionLocalProvider(
                    LocalWorkspacePageHosts provides pageHosts,
                    LocalWorkspaceButtonProvider provides provider,
                ) {
                    AdaptiveWorkspaceLayout(
                        design = WorkspaceDesign(layout = WorkspaceDesign.Layout.DUAL_VERTICAL),
                        workspaces = listOf(tab),
                        selected = mapOf(0 to tab.asPaneInfo()),
                        focusedId = tab.id,
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
        composeTestRule.waitForIdle()
    }

    @Test
    fun `tapping the rail plus opens the templates picker the way the Butler menu does`() {
        val provider = RecordingButtonProvider()
        setLayout(provider)

        composeTestRule.onNodeWithContentDescription(addTabDescription).performClick()
        composeTestRule.waitForIdle()

        provider.templatesRequests shouldBe 1
        provider.actions.filterIsInstance<WorkspaceAction.Create>() shouldBe emptyList()
    }
}
