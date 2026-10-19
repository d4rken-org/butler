package eu.darken.butler.searcher.ui.search.items

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.searcher.core.SearcherViewStyle
import eu.darken.butler.searcher.ui.search.preview.SearcherMockDataProvider
import eu.darken.butler.workspace.R
import eu.darken.butler.workspace.core.appattribution.AppAttribution
import eu.darken.butler.workspace.ui.preview.ProvideFolderPreviews
import kotlinx.coroutines.flow.flowOf
import org.junit.Test
import testhelpers.ComposeTest

class SearchResultAppBadgeTest : ComposeTest() {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val ghostLabel get() = context.getString(R.string.workspace_app_not_installed_content_desc)
    private val ghost = AppAttribution.Ghost("com.example")

    private fun setGrid(attribution: AppAttribution?) {
        composeTestRule.setContent {
            PreviewWrapper {
                ProvideFolderPreviews({ flowOf(emptyList()) }) {
                    Box(Modifier.size(200.dp)) {
                        SelectableFileGrid(
                            result = SearcherMockDataProvider.createMockDirectory(name = "com.example"),
                            density = SearcherViewStyle.Density.COMFORTABLE,
                            isSelected = false,
                            isSelectionMode = false,
                            onClick = {},
                            onLongPress = {},
                            appAttribution = attribution,
                        )
                    }
                }
            }
        }
    }

    private fun setRow(attribution: AppAttribution?, isSelectionMode: Boolean = false) {
        composeTestRule.setContent {
            PreviewWrapper {
                SelectableFileRow(
                    result = SearcherMockDataProvider.createMockDirectory(name = "com.example"),
                    density = SearcherViewStyle.Density.COMFORTABLE,
                    isSelected = false,
                    isSelectionMode = isSelectionMode,
                    onClick = {},
                    onLongPress = {},
                    appAttribution = attribution,
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
        setGrid(null)
        composeTestRule.onNodeWithContentDescription(ghostLabel, useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun `row shows the attribution badge`() {
        setRow(ghost)
        composeTestRule.onNodeWithContentDescription(ghostLabel, useUnmergedTree = true).assertExists()
    }

    @Test
    fun `row hides the badge behind the selection checkbox`() {
        setRow(ghost, isSelectionMode = true)
        composeTestRule.onNodeWithContentDescription(ghostLabel, useUnmergedTree = true).assertDoesNotExist()
    }
}
