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

    // Every case renders multi-pane on purpose: that is the branch without a workspace button, and
    // the switcher's animated mascot never idles under Robolectric.
    private val multiPane = WorkspaceDesign(layout = WorkspaceDesign.Layout.DUAL_VERTICAL)

    @Test
    fun `toolbar shows app name but not package name`() {
        composeTestRule.setContent {
            PreviewWrapper {
                // An overview: no sub-screen to go back from.
                AppDetailsToolbarCard(
                    app = AppsMockDataProvider.Presets.chrome,
                    design = multiPane,
                )
            }
        }

        composeTestRule.onNodeWithText("Chrome").assertIsDisplayed()
        composeTestRule.onNodeWithText("com.android.chrome").assertDoesNotExist()
    }

    @Test
    fun `toolbar joins app name and subtitle into one line`() {
        composeTestRule.setContent {
            PreviewWrapper {
                // The Components sub-screen: its back button returns to the overview.
                AppDetailsToolbarCard(
                    app = AppsMockDataProvider.Presets.chrome,
                    design = multiPane,
                    subtitle = "Components",
                    onBackClick = {},
                )
            }
        }

        // One line in both states: stacking them would push the bar past the workspace button.
        composeTestRule.onNodeWithText("Chrome · Components").assertIsDisplayed()
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

        composeTestRule.onNodeWithText("Chrome · Components").assertIsDisplayed()
    }

    @Test
    fun `an open search field hides the back button`() {
        composeTestRule.setContent {
            var searchActive by remember { mutableStateOf(false) }
            PreviewWrapper {
                AppDetailsToolbarCard(
                    app = AppsMockDataProvider.Presets.chrome,
                    design = WorkspaceDesign(),
                    isModal = true,
                    subtitle = "Components",
                    onBackClick = {},
                    backContentDescription = "Back to app details",
                    searchActive = searchActive,
                    searchQuery = TextFieldValue(),
                    onSearchToggle = { searchActive = !searchActive },
                )
            }
        }

        composeTestRule.onNodeWithContentDescription("Back to app details").assertIsDisplayed()

        // The back arrow and the app icon step aside to make room for the query; closing the search
        // is what brings them back.
        composeTestRule.onNodeWithContentDescription("Search components").performClick()
        composeTestRule.onNodeWithContentDescription("Back to app details").assertDoesNotExist()

        composeTestRule.onNodeWithContentDescription("Close").performClick()
        composeTestRule.onNodeWithContentDescription("Back to app details").assertIsDisplayed()
    }

    @Test
    fun `toggling search replaces the title with the input`() {
        composeTestRule.setContent {
            var searchActive by remember { mutableStateOf(false) }
            PreviewWrapper {
                AppDetailsToolbarCard(
                    app = AppsMockDataProvider.Presets.chrome,
                    design = multiPane,
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
                    design = multiPane,
                    subtitle = "Components",
                    onBackClick = {},
                )
            }
        }

        composeTestRule.onNodeWithContentDescription("Search components").assertDoesNotExist()
    }
}
