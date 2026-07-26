package eu.darken.butler.searcher.ui.search

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.searcher.ui.search.preview.SearcherMockDataProvider
import eu.darken.butler.searcher.ui.search.util.SearcherPageAction
import eu.darken.butler.workspace.core.operations.Operation
import eu.darken.butler.workspace.ui.dialogs.PaneBoundAlertDialogDefaults
import eu.darken.butler.workspace.ui.modal.PaneLayerHost
import eu.darken.butler.workspace.ui.operations.OperationDisplay
import eu.darken.butler.workspace.ui.operations.OperationsDisplayState
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.flowOf
import org.junit.Test
import testhelpers.ComposeTest

/**
 * The searcher routes the operations bar through an overlay action pair instead of cancelling
 * directly, so the confirmation is rendered by its overlay slot from ViewModel state.
 */
class SearcherCancelConfirmationTest : ComposeTest() {

    private val surface = PaneBoundAlertDialogDefaults.SURFACE_TEST_TAG

    @Composable
    private fun Overlays(
        operations: List<OperationDisplay>,
        cancelConfirmationFor: Operation.Id?,
        onPageAction: (SearcherPageAction) -> Unit = {},
    ) {
        PreviewWrapper {
            PaneLayerHost(modifier = Modifier.fillMaxSize(), paneFocused = true) {
                SearcherWorkspaceOverlays(
                    stateSource = flowOf(SearcherMockDataProvider.createMockEmptyState()),
                    operationsStateSource = flowOf(OperationsDisplayState(operations = operations)),
                    overlayState = SearcherWorkspaceViewModel.OverlayState(
                        cancelOperationConfirmationFor = cancelConfirmationFor,
                    ),
                    onPageAction = onPageAction,
                )
            }
        }
    }

    @Test
    fun `a pending cancel renders the confirmation in the overlay slot`() {
        val operation = SearcherMockDataProvider.createMockRunningOperation()

        composeTestRule.setContent {
            Overlays(operations = listOf(operation), cancelConfirmationFor = operation.id)
        }

        composeTestRule.onNodeWithTag(surface).assertExists()
    }

    @Test
    fun `no pending cancel renders no confirmation`() {
        val operation = SearcherMockDataProvider.createMockRunningOperation()

        composeTestRule.setContent {
            Overlays(operations = listOf(operation), cancelConfirmationFor = null)
        }

        composeTestRule.onNodeWithTag(surface).assertDoesNotExist()
    }

    @Test
    fun `confirming cancels the operation and clears the request`() {
        val operation = SearcherMockDataProvider.createMockRunningOperation()
        val actions = mutableListOf<SearcherPageAction>()

        composeTestRule.setContent {
            Overlays(
                operations = listOf(operation),
                cancelConfirmationFor = operation.id,
                onPageAction = { actions.add(it) },
            )
        }

        composeTestRule.onNodeWithText(CANCEL_OPERATION_ACTION).performClick()

        composeTestRule.runOnIdle {
            actions shouldBe listOf(
                SearcherPageAction.Operations.Cancel(operation.id),
                SearcherPageAction.Overlays.DismissCancelOperation,
            )
        }
    }

    companion object {
        /** `operations_cancel_operation`. */
        private const val CANCEL_OPERATION_ACTION = "Cancel operation"
    }
}
