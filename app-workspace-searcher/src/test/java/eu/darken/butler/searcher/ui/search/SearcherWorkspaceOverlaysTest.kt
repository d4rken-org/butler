package eu.darken.butler.searcher.ui.search

import android.content.Context
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.searcher.R
import eu.darken.butler.searcher.ui.search.preview.SearcherMockDataProvider
import eu.darken.butler.workspace.ui.modal.PaneLayerHost
import kotlinx.coroutines.flow.flowOf
import org.junit.Test
import testhelpers.ComposeTest

/** The searcher page's dialogs and sheets render from the overlay slot, not from the page. */
class SearcherWorkspaceOverlaysTest : ComposeTest() {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `nothing renders while no overlay is requested`() {
        composeTestRule.setContent {
            PreviewWrapper {
                PaneLayerHost(modifier = Modifier.fillMaxSize(), paneFocused = true) {
                    SearcherWorkspaceOverlays(
                        stateSource = flowOf(SearcherMockDataProvider.createMockEmptyState()),
                        overlayState = SearcherWorkspaceViewModel.OverlayState(),
                    )
                }
            }
        }

        composeTestRule
            .onNodeWithText(context.getString(R.string.searcher_search_error))
            .assertDoesNotExist()
    }

    @Test
    fun `the target error dialog renders from the overlay slot`() {
        composeTestRule.setContent {
            PreviewWrapper {
                PaneLayerHost(modifier = Modifier.fillMaxSize(), paneFocused = true) {
                    SearcherWorkspaceOverlays(
                        stateSource = flowOf(SearcherMockDataProvider.createMockEmptyState()),
                        overlayState = SearcherWorkspaceViewModel.OverlayState(
                            targetError = SearcherWorkspaceViewModel.TargetError(
                                path = "/storage/emulated/0/Android/data",
                                error = IllegalStateException("Permission denied"),
                            ),
                        ),
                    )
                }
            }
        }

        composeTestRule
            .onNodeWithText(context.getString(R.string.searcher_search_error))
            .assertIsDisplayed()
    }
}
