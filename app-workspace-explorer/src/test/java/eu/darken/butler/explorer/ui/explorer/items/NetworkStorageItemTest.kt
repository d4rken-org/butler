package eu.darken.butler.explorer.ui.explorer.items

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.explorer.core.engine.ExplorerItem
import eu.darken.butler.explorer.ui.explorer.items.grid.StorageGrid
import eu.darken.butler.explorer.ui.explorer.items.row.StorageRow
import eu.darken.butler.explorer.ui.explorer.preview.MockDataProvider
import io.kotest.matchers.shouldBe
import org.junit.Test
import testhelpers.ComposeTest

class NetworkStorageItemTest : ComposeTest() {

    private val available = MockDataProvider.createMockStorageNetwork(name = "Home NAS")
    private val signInRequired = MockDataProvider.createMockStorageNetwork(
        name = "Work NAS",
        status = ExplorerItem.Storage.Network.Status.SIGN_IN_REQUIRED,
    )

    /**
     * Selecting a row is how Remove, Edit and Rename reach a location, so a broken one has to stay
     * selectable. Keeping it out of a picker result is the picker's job, not the item's.
     */
    @Test
    fun `a location that needs a sign-in stays selectable`() {
        available.isSelectable() shouldBe true
        signInRequired.isSelectable() shouldBe true
    }

    @Test
    fun `the row shows the label and endpoint, never the internal path`() {
        composeTestRule.setContent {
            PreviewWrapper {
                StorageRow(item = available, onClick = {})
            }
        }

        composeTestRule.onNodeWithText("Home NAS").assertIsDisplayed()
        composeTestRule.onNodeWithText("nas.local/media").assertIsDisplayed()
        composeTestRule.onAllNodes(hasText(available.target.path.path)).fetchSemanticsNodes().size shouldBe 0
    }

    @Test
    fun `the row badges a location that needs a sign-in`() {
        composeTestRule.setContent {
            PreviewWrapper {
                StorageRow(item = signInRequired, onClick = {})
            }
        }

        composeTestRule.onNodeWithText("Sign-in required").assertIsDisplayed()
    }

    @Test
    fun `the grid tile shows the endpoint, never the internal path`() {
        composeTestRule.setContent {
            PreviewWrapper {
                Box(Modifier.size(200.dp)) {
                    StorageGrid(item = available, onClick = {})
                }
            }
        }

        composeTestRule.onNodeWithText("Home NAS").assertIsDisplayed()
        composeTestRule.onNodeWithText("nas.local/media").assertIsDisplayed()
        composeTestRule.onAllNodes(hasText(available.target.path.path)).fetchSemanticsNodes().size shouldBe 0
    }

    /** The tile has no room for a label, so the badge is the icon in the top-right corner. */
    @Test
    fun `the grid tile badges a location that needs a sign-in`() {
        composeTestRule.setContent {
            PreviewWrapper {
                Box(Modifier.size(200.dp)) {
                    StorageGrid(item = signInRequired, onClick = {})
                }
            }
        }

        composeTestRule.onNodeWithContentDescription("Sign-in required").assertIsDisplayed()
    }
}
