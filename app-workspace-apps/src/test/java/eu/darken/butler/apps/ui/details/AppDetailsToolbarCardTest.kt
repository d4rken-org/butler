package eu.darken.butler.apps.ui.details

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import eu.darken.butler.apps.ui.apps.preview.AppsMockDataProvider
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.workspace.ui.manager.WorkspaceDesign
import org.junit.Test
import testhelpers.ComposeTest

class AppDetailsToolbarCardTest : ComposeTest() {

    @Test
    fun `toolbar shows app name but not package name`() {
        composeTestRule.setContent {
            PreviewWrapper {
                AppDetailsToolbarCard(
                    app = AppsMockDataProvider.Presets.chrome,
                    design = WorkspaceDesign(),
                    // Provide a back handler so the workspace switcher (which needs a provider) is hidden.
                    onBackClick = {},
                )
            }
        }

        composeTestRule.onNodeWithText("Chrome").assertIsDisplayed()
        composeTestRule.onNodeWithText("com.android.chrome").assertDoesNotExist()
    }

    @Test
    fun `toolbar renders a title override`() {
        composeTestRule.setContent {
            PreviewWrapper {
                AppDetailsToolbarCard(
                    app = AppsMockDataProvider.Presets.chrome,
                    design = WorkspaceDesign(),
                    title = "Components",
                    onBackClick = {},
                )
            }
        }

        composeTestRule.onNodeWithText("Components").assertIsDisplayed()
        composeTestRule.onNodeWithText("Chrome").assertDoesNotExist()
    }
}
