package eu.darken.butler.apps.ui.details

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import eu.darken.butler.apps.core.AppPath
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.files.LocalPath
import org.junit.Test
import testhelpers.ComposeTest

class StorageListItemsTest : ComposeTest() {

    private fun setContent(requirement: String?) {
        composeTestRule.setContent {
            PreviewWrapper {
                StorageListItems(
                    availablePaths = listOf(
                        AppPath(
                            path = LocalPath.build(PATH),
                            label = "Internal data".toCaString(),
                            requirement = requirement?.toCaString(),
                        ),
                    ),
                    onBrowsePath = {},
                    onOpenSetup = {},
                )
            }
        }
    }

    /** Browsing there is the route to the permission card, so the row must not become inert. */
    @Test
    fun `a row that needs access says so and stays clickable`() {
        setContent("Requires root")

        composeTestRule.onNodeWithText("Requires root").assertIsDisplayed()
        composeTestRule.onNode(hasText("Requires root") and hasClickAction()).assertExists()
    }

    @Test
    fun `a row with access says nothing extra`() {
        setContent(null)

        composeTestRule.onNodeWithText(PATH).assertIsDisplayed()
        composeTestRule.onNodeWithText("Requires root").assertDoesNotExist()
    }

    companion object {
        private const val PATH = "/data/data/eu.darken.butler"
    }
}
