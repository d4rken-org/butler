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
                // A modal overview: no sub-screen to go back from, and no workspace button — the
                // switcher's animated mascot never idles under Robolectric.
                AppDetailsToolbarCard(
                    app = AppsMockDataProvider.Presets.chrome,
                    design = WorkspaceDesign(),
                    isModal = true,
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
                // The Components sub-screen of a modal: its back button returns to the overview.
                AppDetailsToolbarCard(
                    app = AppsMockDataProvider.Presets.chrome,
                    design = WorkspaceDesign(),
                    isModal = true,
                    subtitle = "Components",
                    onBackClick = {},
                )
            }
        }

        composeTestRule.onNodeWithText("Chrome").assertIsDisplayed()
        composeTestRule.onNodeWithText("Components").assertIsDisplayed()
    }

    @Test
    fun `collapsed toolbar joins app name and subtitle into one line`() {
        composeTestRule.setContent {
            PreviewWrapper {
                AppDetailsToolbarCard(
                    app = AppsMockDataProvider.Presets.chrome,
                    design = WorkspaceDesign(),
                    isModal = true,
                    collapsedFraction = 1f,
                    subtitle = "Components",
                    onBackClick = {},
                )
            }
        }

        // One line keeps the collapsed bar at the same height as the other workspaces' toolbars.
        composeTestRule.onNodeWithText("Chrome · Components").assertIsDisplayed()
    }

    @Test
    fun `toggling search replaces the title with the input`() {
        composeTestRule.setContent {
            var searchActive by remember { mutableStateOf(false) }
            PreviewWrapper {
                AppDetailsToolbarCard(
                    app = AppsMockDataProvider.Presets.chrome,
                    design = WorkspaceDesign(),
                    isModal = true,
                    subtitle = "Components",
                    onBackClick = {},
                    searchActive = searchActive,
                    searchQuery = TextFieldValue(),
                    onSearchToggle = { searchActive = !searchActive },
                )
            }
        }

        composeTestRule.onNodeWithContentDescription("Search components").performClick()

        // The icon's description and the field's hint are deliberately different strings, so the
        // open and closed states are distinguishable from the semantics tree alone.
        composeTestRule.onNodeWithText("Search name or package").assertIsDisplayed()
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
                    isModal = true,
                    subtitle = "Components",
                    onBackClick = {},
                )
            }
        }

        composeTestRule.onNodeWithContentDescription("Search components").assertDoesNotExist()
    }
}
