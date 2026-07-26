package eu.darken.butler.explorer.ui.explorer

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.onNodeWithTag
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.explorer.ui.explorer.preview.MockDataProvider
import eu.darken.butler.workspace.core.operations.Operation
import eu.darken.butler.workspace.ui.dialogs.PaneBoundAlertDialogDefaults
import eu.darken.butler.workspace.ui.modal.PaneLayerHost
import eu.darken.butler.workspace.ui.operations.OperationsDisplayState
import org.junit.Test
import testhelpers.ComposeTest

/**
 * The explorer's cancel confirmation is no longer opened by the operations bar, it is opened by the
 * overlay slot from ViewModel state. A generic dialog test cannot show that this particular wire is
 * connected.
 */
class ExplorerCancelConfirmationTest : ComposeTest() {

    private val surface = PaneBoundAlertDialogDefaults.SURFACE_TEST_TAG

    @Composable
    private fun Overlays(
        operationsState: OperationsDisplayState,
        cancelConfirmationFor: Operation.Id?,
    ) {
        PreviewWrapper {
            PaneLayerHost(modifier = Modifier.fillMaxSize(), paneFocused = true) {
                ExplorerWorkspaceOverlays(
                    operationsState = operationsState,
                    cancelConfirmationFor = cancelConfirmationFor,
                )
            }
        }
    }

    @Test
    fun `a pending cancel renders the confirmation in the overlay slot`() {
        val operation = MockDataProvider.createMockRunningOperation()

        composeTestRule.setContent {
            Overlays(
                operationsState = OperationsDisplayState(operations = listOf(operation)),
                cancelConfirmationFor = operation.id,
            )
        }

        composeTestRule.onNodeWithTag(surface).assertExists()
    }

    @Test
    fun `no pending cancel renders no confirmation`() {
        val operation = MockDataProvider.createMockRunningOperation()

        composeTestRule.setContent {
            Overlays(
                operationsState = OperationsDisplayState(operations = listOf(operation)),
                cancelConfirmationFor = null,
            )
        }

        composeTestRule.onNodeWithTag(surface).assertDoesNotExist()
    }

    @Test
    fun `a pending cancel for a finished operation renders no confirmation`() {
        val running = MockDataProvider.createMockRunningOperation()
        var operations by mutableStateOf(listOf(running))

        composeTestRule.setContent {
            Overlays(
                operationsState = OperationsDisplayState(operations = operations),
                cancelConfirmationFor = running.id,
            )
        }

        composeTestRule.onNodeWithTag(surface).assertExists()

        composeTestRule.runOnIdle {
            operations = listOf(MockDataProvider.createMockCompletedOperation().copy(id = running.id))
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag(surface).assertDoesNotExist()
    }
}
