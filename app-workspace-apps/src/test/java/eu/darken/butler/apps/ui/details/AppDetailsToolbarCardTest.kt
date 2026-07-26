package eu.darken.butler.apps.ui.details

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.input.TextFieldValue
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
    fun `toolbar shows the app name alongside a subtitle`() {
        composeTestRule.setContent {
            PreviewWrapper {
                AppDetailsToolbarCard(
                    app = AppsMockDataProvider.Presets.chrome,
                    design = WorkspaceDesign(),
                    subtitle = "Components",
                    onBackClick = {},
                )
            }
        }

        composeTestRule.onNodeWithText("Chrome").assertIsDisplayed()
        composeTestRule.onNodeWithText("Components").assertIsDisplayed()
    }

    @Test
    fun `toggling search replaces the title with the input`() {
        composeTestRule.setContent {
            var searchActive by remember { mutableStateOf(false) }
            PreviewWrapper {
                AppDetailsToolbarCard(
                    app = AppsMockDataProvider.Presets.chrome,
                    design = WorkspaceDesign(),
                    subtitle = "Components",
                    onBackClick = {},
                    searchActive = searchActive,
                    searchQuery = TextFieldValue(),
                    onSearchToggle = { searchActive = !searchActive },
                )
            }
        }

        composeTestRule.onNodeWithContentDescription("Search components").performClick()

        composeTestRule.onNodeWithText("Search components").assertIsDisplayed()
        composeTestRule.onNodeWithText("Chrome").assertDoesNotExist()
        composeTestRule.onNodeWithContentDescription("Close").assertIsDisplayed()
    }

    @Test
    fun `no search affordance without a toggle handler`() {
        composeTestRule.setContent {
            PreviewWrapper {
                AppDetailsToolbarCard(
                    app = AppsMockDataProvider.Presets.chrome,
                    design = WorkspaceDesign(),
                    subtitle = "Components",
                    onBackClick = {},
                )
            }
        }

        composeTestRule.onNodeWithContentDescription("Search components").assertDoesNotExist()
    }
}
