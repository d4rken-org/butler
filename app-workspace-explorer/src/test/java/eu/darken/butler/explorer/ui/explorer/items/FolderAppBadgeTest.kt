package eu.darken.butler.explorer.ui.explorer.items

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.explorer.core.ExplorerViewStyle
import eu.darken.butler.explorer.ui.explorer.items.grid.LookupItemGrid
import eu.darken.butler.explorer.ui.explorer.items.row.LookupItemRow
import eu.darken.butler.explorer.ui.explorer.preview.MockDataProvider
import eu.darken.butler.workspace.R
import eu.darken.butler.workspace.core.appattribution.AppAttribution
import eu.darken.butler.workspace.ui.preview.ProvideFolderPreviews
import kotlinx.coroutines.flow.flowOf
import org.junit.Test
import testhelpers.ComposeTest

class FolderAppBadgeTest : ComposeTest() {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val ghostLabel get() = context.getString(R.string.workspace_app_not_installed_content_desc)
    private val ghost = ItemDecorations(appAttribution = AppAttribution.Ghost("com.example"))

    private fun setGrid(decorations: ItemDecorations, showSelection: Boolean = false) {
        composeTestRule.setContent {
            PreviewWrapper {
                ProvideFolderPreviews({ flowOf(emptyList()) }) {
                    Box(Modifier.size(200.dp)) {
                        LookupItemGrid(
                            item = MockDataProvider.createMockDirectory(name = "com.example"),
                            density = ExplorerViewStyle.Density.COMFORTABLE,
                            isSelected = false,
                            onToggleSelection = {},
                            onClick = {},
                            showSelection = showSelection,
                            decorations = decorations,
                        )
                    }
                }
            }
        }
    }

    private fun setRow(decorations: ItemDecorations, showSelection: Boolean = false) {
        composeTestRule.setContent {
            PreviewWrapper {
                LookupItemRow(
                    item = MockDataProvider.createMockDirectory(name = "com.example"),
                    density = ExplorerViewStyle.Density.COMFORTABLE,
                    isSelected = false,
                    onToggleSelection = {},
                    onClick = {},
                    showSelection = showSelection,
                    decorations = decorations,
                )
            }
        }
    }

    @Test
    fun `grid tile shows the attribution badge`() {
        setGrid(ghost)
        composeTestRule.onNodeWithContentDescription(ghostLabel, useUnmergedTree = true).assertExists()
    }

    @Test
    fun `grid tile without attribution shows no badge`() {
        setGrid(ItemDecorations())
        composeTestRule.onNodeWithContentDescription(ghostLabel, useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun `grid tile keeps the badge in selection mode`() {
        setGrid(ghost, showSelection = true)
        composeTestRule.onNodeWithContentDescription(ghostLabel, useUnmergedTree = true).assertExists()
    }

    @Test
    fun `row shows the attribution badge`() {
        setRow(ghost)
        composeTestRule.onNodeWithContentDescription(ghostLabel, useUnmergedTree = true).assertExists()
    }

    @Test
    fun `row hides the badge behind the selection checkbox`() {
        setRow(ghost, showSelection = true)
        composeTestRule.onNodeWithContentDescription(ghostLabel, useUnmergedTree = true).assertDoesNotExist()
    }
}
