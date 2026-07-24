package eu.darken.butler.apps.ui.details

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import eu.darken.butler.apps.core.details.AppDetailsWorkspace
import eu.darken.butler.apps.ui.apps.preview.AppsMockDataProvider
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.workspace.contracts.apps.DetailTab
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.ui.manager.WorkspaceDesign
import io.kotest.matchers.shouldBe
import org.junit.Test
import testhelpers.ComposeTest

class AppDetailsWorkspacePageTest : ComposeTest() {

    @Test
    fun `components route shows components title and back returns to overview`() {
        val actions = mutableListOf<AppDetailsPageAction>()
        composeTestRule.setContent {
            PreviewWrapper {
                AppDetailsWorkspacePage(
                    design = WorkspaceDesign(),
                    state = AppDetailsWorkspace.State(
                        app = AppsMockDataProvider.Presets.chrome,
                        selectedTab = DetailTab.COMPONENTS,
                    ),
                    workspaceId = Workspace.Id(),
                    onPageAction = { actions += it },
                )
            }
        }

        composeTestRule.onNodeWithText("Components").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Back").performClick()

        actions shouldBe listOf(AppDetailsPageAction.NavigateToTab(DetailTab.OVERVIEW))
    }

    @Test
    fun `overview shows package name once - not duplicated in the toolbar`() {
        composeTestRule.setContent {
            PreviewWrapper {
                AppDetailsWorkspacePage(
                    design = WorkspaceDesign(),
                    state = AppDetailsWorkspace.State(
                        app = AppsMockDataProvider.Presets.chrome,
                        selectedTab = DetailTab.OVERVIEW,
                        // Modal so the toolbar renders a back button instead of the workspace switcher.
                        callerWorkspaceId = Workspace.Id(),
                    ),
                    workspaceId = Workspace.Id(),
                    onPageAction = {},
                )
            }
        }

        // App name is in the toolbar; the package name appears only in the overview card (exactly one).
        composeTestRule.onNodeWithText("Chrome").assertIsDisplayed()
        composeTestRule.onNodeWithText("com.android.chrome").assertIsDisplayed()
    }
}
