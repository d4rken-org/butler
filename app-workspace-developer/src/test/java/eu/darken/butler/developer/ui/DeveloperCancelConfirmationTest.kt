package eu.darken.butler.developer.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.Delete
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.workspace.core.operations.Operation
import eu.darken.butler.workspace.ui.dialogs.PaneBoundAlertDialogDefaults
import eu.darken.butler.workspace.ui.modal.PaneLayerHost
import eu.darken.butler.workspace.ui.operations.OperationDisplay
import io.kotest.matchers.shouldBe
import org.junit.Test
import testhelpers.ComposeTest
import kotlin.time.Clock

/**
 * The developer page had no overlay slot at all, so its cancel confirmation could only ever be a
 * local `remember` inside the page — which a sibling overlay composition cannot observe.
 */
class DeveloperCancelConfirmationTest : ComposeTest() {

    private val surface = PaneBoundAlertDialogDefaults.SURFACE_TEST_TAG

    private fun runningOperation(id: Operation.Id = Operation.Id()) = OperationDisplay(
        id = id,
        startedAt = Clock.System.now(),
        icon = Icons.TwoTone.Delete,
        title = "Generating test data".toCaString(),
        description = "3 of 10".toCaString(),
        canCancel = true,
        state = OperationDisplay.State.Running(),
    )

    @Composable
    private fun Overlays(
        operations: List<OperationDisplay>,
        cancelConfirmationFor: Operation.Id?,
        onCancelOperation: (Operation.Id) -> Unit = {},
        onDismissCancelConfirmation: () -> Unit = {},
    ) {
        PreviewWrapper {
            PaneLayerHost(modifier = Modifier.fillMaxSize(), paneFocused = true) {
                DeveloperWorkspaceOverlays(
                    operations = operations,
                    cancelConfirmationFor = cancelConfirmationFor,
                    onDismissCancelConfirmation = onDismissCancelConfirmation,
                    onCancelOperation = onCancelOperation,
                )
            }
        }
    }

    @Test
    fun `a pending cancel renders the confirmation in the overlay slot`() {
        val operation = runningOperation()

        composeTestRule.setContent {
            Overlays(operations = listOf(operation), cancelConfirmationFor = operation.id)
        }

        composeTestRule.onNodeWithTag(surface).assertExists()
    }

    @Test
    fun `no pending cancel renders no confirmation`() {
        val operation = runningOperation()

        composeTestRule.setContent {
            Overlays(operations = listOf(operation), cancelConfirmationFor = null)
        }

        composeTestRule.onNodeWithTag(surface).assertDoesNotExist()
    }

    @Test
    fun `confirming cancels the operation and clears the request`() {
        val operation = runningOperation()
        val cancelled = mutableListOf<Operation.Id>()
        var dismissals = 0

        composeTestRule.setContent {
            Overlays(
                operations = listOf(operation),
                cancelConfirmationFor = operation.id,
                onCancelOperation = { cancelled.add(it) },
                onDismissCancelConfirmation = { dismissals++ },
            )
        }

        composeTestRule.onNodeWithText(CANCEL_OPERATION_ACTION).performClick()

        composeTestRule.runOnIdle {
            cancelled shouldBe listOf(operation.id)
            dismissals shouldBe 1
        }
    }

    companion object {
        /** `operations_cancel_operation`. */
        private const val CANCEL_OPERATION_ACTION = "Cancel operation"
    }
}
