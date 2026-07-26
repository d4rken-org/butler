package eu.darken.butler.history.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.workspace.core.operations.history.HistoryFilter
import eu.darken.butler.workspace.ui.modal.PaneLayerHost
import org.junit.Test
import testhelpers.ComposeTest

/** The history page's sheets and dialogs render from the overlay slot, not from the page. */
class HistoryWorkspaceOverlaysTest : ComposeTest() {

    @Test
    fun `nothing renders while no overlay is requested`() {
        composeTestRule.setContent {
            PreviewWrapper {
                PaneLayerHost(modifier = Modifier.fillMaxSize(), paneFocused = true) {
                    HistoryWorkspaceOverlays(
                        filter = HistoryFilter(),
                        overlayState = HistoryWorkspaceViewModel.OverlayState(),
                    )
                }
            }
        }

        composeTestRule.onNode(hasSetTextAction()).assertDoesNotExist()
    }

    @Test
    fun `the path scope dialog renders from the overlay slot`() {
        composeTestRule.setContent {
            PreviewWrapper {
                PaneLayerHost(modifier = Modifier.fillMaxSize(), paneFocused = true) {
                    HistoryWorkspaceOverlays(
                        filter = HistoryFilter(),
                        overlayState = HistoryWorkspaceViewModel.OverlayState(pathScopeOpen = true),
                    )
                }
            }
        }

        composeTestRule.onNode(hasSetTextAction()).assertIsDisplayed()
    }
}
