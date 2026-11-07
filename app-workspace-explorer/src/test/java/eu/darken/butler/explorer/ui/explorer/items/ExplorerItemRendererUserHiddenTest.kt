package eu.darken.butler.explorer.ui.explorer.items

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.explorer.core.ExplorerViewStyle
import eu.darken.butler.explorer.core.engine.ExplorerItem
import eu.darken.butler.explorer.core.engine.ExplorerLocation
import eu.darken.butler.explorer.ui.explorer.ExplorerWorkspaceViewModel
import eu.darken.butler.explorer.ui.explorer.preview.MockDataProvider
import org.junit.Test
import testhelpers.ComposeTest

/** Robolectric cannot draw, so this only pins that a dimmed user-hidden entry still renders. */
class ExplorerItemRendererUserHiddenTest : ComposeTest() {

    @Test
    fun `list rows render a user-hidden entry next to a normal one`() {
        val hidden = MockDataProvider.createMockDirectory(HIDDEN_NAME)
        val normal = MockDataProvider.createMockDirectory(NORMAL_NAME)
        renderItems(
            items = listOf(normal, hidden),
            mode = ExplorerViewStyle.Mode.LIST,
            state = MockDataProvider.createReadyState().copy(userHiddenIds = setOf(hidden.id)),
        )

        composeTestRule.onNodeWithText(NORMAL_NAME).assertIsDisplayed()
        composeTestRule.onNodeWithText(HIDDEN_NAME).assertIsDisplayed()
    }

    @Test
    fun `grid cells render a user-hidden entry next to a normal one`() {
        val hidden = MockDataProvider.createMockRegularFile(HIDDEN_FILE_NAME)
        val normal = MockDataProvider.createMockRegularFile(NORMAL_FILE_NAME)
        renderItems(
            items = listOf(normal, hidden),
            mode = ExplorerViewStyle.Mode.GRID,
            state = MockDataProvider.createReadyState().copy(userHiddenIds = setOf(hidden.id)),
        )

        composeTestRule.onNodeWithText(NORMAL_FILE_NAME).assertExists()
        composeTestRule.onNodeWithText(HIDDEN_FILE_NAME).assertExists()
    }

    @Test
    fun `recent rows render a user-hidden entry next to a normal one`() {
        val hidden = MockDataProvider.createMockRecentFile(name = HIDDEN_FILE_NAME)
        val normal = MockDataProvider.createMockRecentFile(name = NORMAL_FILE_NAME)
        renderItems(
            items = listOf(normal, hidden),
            mode = ExplorerViewStyle.Mode.LIST,
            state = MockDataProvider.createReadyState().copy(
                currentLocation = ExplorerLocation.Recent(items = listOf(normal, hidden)),
                userHiddenIds = setOf(hidden.id),
            ),
        )

        composeTestRule.onNodeWithText(NORMAL_FILE_NAME).assertIsDisplayed()
        composeTestRule.onNodeWithText(HIDDEN_FILE_NAME).assertIsDisplayed()
    }

    private fun renderItems(
        items: List<ExplorerItem>,
        mode: ExplorerViewStyle.Mode,
        state: ExplorerWorkspaceViewModel.State,
    ) {
        composeTestRule.setContent {
            PreviewWrapper {
                Column {
                    items.forEach { item ->
                        ExplorerItemRenderer(
                            item = item,
                            viewStyle = ExplorerViewStyle(mode = mode),
                            state = state,
                            isFocused = false,
                            onItemClick = {},
                            onItemLongClick = {},
                            onItemMoreClick = {},
                            onNavigate = {},
                            onToggleSelection = {},
                        )
                    }
                }
            }
        }
    }

    companion object {
        private const val NORMAL_NAME = "Visible folder"
        private const val HIDDEN_NAME = "Hidden folder"
        private const val NORMAL_FILE_NAME = "visible.txt"
        private const val HIDDEN_FILE_NAME = "hidden.txt"
    }
}
