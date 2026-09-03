package eu.darken.butler.history.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.workspace.core.operations.Operation
import eu.darken.butler.workspace.core.operations.history.HistoryFilter
import eu.darken.butler.workspace.ui.modal.PaneLayerHost
import io.kotest.matchers.shouldBe
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
    fun `the add-filter reset is disabled while the filter is empty`() {
        composeTestRule.setContent {
            PreviewWrapper {
                PaneLayerHost(modifier = Modifier.fillMaxSize(), paneFocused = true) {
                    HistoryWorkspaceOverlays(
                        filter = HistoryFilter(),
                        overlayState = HistoryWorkspaceViewModel.OverlayState(addFilterOpen = true),
                    )
                }
            }
        }

        composeTestRule.onNodeWithText("Reset").assertIsNotEnabled()
    }

    @Test
    fun `the add-filter reset fires once the filter has values`() {
        var reset = false
        composeTestRule.setContent {
            PreviewWrapper {
                PaneLayerHost(modifier = Modifier.fillMaxSize(), paneFocused = true) {
                    HistoryWorkspaceOverlays(
                        filter = HistoryFilter(kinds = setOf(Operation.Metadata.Kind.DELETE)),
                        overlayState = HistoryWorkspaceViewModel.OverlayState(addFilterOpen = true),
                        onResetFilter = { reset = true },
                    )
                }
            }
        }

        composeTestRule.onNodeWithText("Reset").assertIsEnabled().performClick()
        reset shouldBe true
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
