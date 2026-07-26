package eu.darken.butler.workspace.ui.operations

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.Delete
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.workspace.core.operations.Operation
import eu.darken.butler.workspace.ui.dialogs.PaneBoundAlertDialogDefaults
import eu.darken.butler.workspace.ui.modal.PaneLayerHost
import eu.darken.butler.workspace.ui.operations.bar.OperationsBar
import eu.darken.butler.workspace.ui.operations.details.CancelOperationConfirmationHost
import io.kotest.matchers.shouldBe
import org.junit.Test
import testhelpers.ComposeTest
import kotlin.time.Clock

/**
 * The confirmation used to live inside [OperationsBar] and could not outlive it. Hoisted to the
 * pane's overlay slot it can, so it has to police the pending id itself.
 */
class CancelOperationConfirmationHostTest : ComposeTest() {

    private val surface = PaneBoundAlertDialogDefaults.SURFACE_TEST_TAG

    private fun operation(
        id: Operation.Id = Operation.Id(),
        canCancel: Boolean = true,
        finished: Boolean = false,
    ) = OperationDisplay(
        id = id,
        startedAt = Clock.System.now(),
        icon = Icons.TwoTone.Delete,
        title = "Deleting files".toCaString(),
        description = "3 of 10".toCaString(),
        canCancel = canCancel,
        state = if (finished) {
            OperationDisplay.State.Cancelled(completedAt = Clock.System.now(), report = null)
        } else {
            OperationDisplay.State.Running()
        },
    )

    @Composable
    private fun Host(
        pendingId: Operation.Id?,
        operations: List<OperationDisplay>,
        onDismiss: () -> Unit = {},
        onConfirm: (Operation.Id) -> Unit = {},
    ) {
        PreviewWrapper {
            PaneLayerHost(modifier = Modifier.fillMaxSize(), paneFocused = true) {
                CancelOperationConfirmationHost(
                    pendingId = pendingId,
                    operations = operations,
                    onDismiss = onDismiss,
                    onConfirm = onConfirm,
                )
            }
        }
    }

    @Test
    fun `a cancelable pending operation shows the confirmation`() {
        val op = operation()
        composeTestRule.setContent { Host(pendingId = op.id, operations = listOf(op)) }

        composeTestRule.onNodeWithTag(surface).assertExists()
    }

    @Test
    fun `no pending id shows nothing`() {
        val op = operation()
        composeTestRule.setContent { Host(pendingId = null, operations = listOf(op)) }

        composeTestRule.onNodeWithTag(surface).assertDoesNotExist()
    }

    @Test
    fun `confirming reports the pending operation`() {
        val op = operation()
        val confirmed = mutableListOf<Operation.Id>()

        composeTestRule.setContent {
            Host(pendingId = op.id, operations = listOf(op), onConfirm = { confirmed.add(it) })
        }

        composeTestRule.onNodeWithText(CANCEL_OPERATION_ACTION).performClick()

        composeTestRule.runOnIdle { confirmed shouldBe listOf(op.id) }
    }

    /**
     * Every host collects its operations with a null initial value and substitutes an empty list,
     * while the pending id comes straight out of durable ViewModel state. Reading that empty first
     * frame as "the operation is gone" retired the request before the dialog was ever on screen.
     */
    @Test
    fun `a pending id survives the frame before the operations list has loaded`() {
        val op = operation()
        var operations by mutableStateOf(emptyList<OperationDisplay>())
        var dismissals = 0

        composeTestRule.setContent {
            Host(pendingId = op.id, operations = operations, onDismiss = { dismissals++ })
        }

        composeTestRule.onNodeWithTag(surface).assertExists()
        composeTestRule.runOnIdle { dismissals shouldBe 0 }

        composeTestRule.runOnIdle { operations = listOf(op) }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag(surface).assertExists()
        composeTestRule.runOnIdle { dismissals shouldBe 0 }
    }

    @Test
    fun `an operation that finishes first takes its own confirmation down`() {
        val id = Operation.Id()
        var operations by mutableStateOf(listOf(operation(id = id)))
        var dismissals = 0

        composeTestRule.setContent {
            Host(pendingId = id, operations = operations, onDismiss = { dismissals++ })
        }

        composeTestRule.onNodeWithTag(surface).assertExists()

        composeTestRule.runOnIdle { operations = listOf(operation(id = id, finished = true)) }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag(surface).assertDoesNotExist()
        composeTestRule.runOnIdle { (dismissals > 0) shouldBe true }
    }

    @Test
    fun `an operation that disappears entirely takes its own confirmation down`() {
        val id = Operation.Id()
        var operations by mutableStateOf(listOf(operation(id = id)))
        var dismissals = 0

        composeTestRule.setContent {
            Host(pendingId = id, operations = operations, onDismiss = { dismissals++ })
        }

        composeTestRule.onNodeWithTag(surface).assertExists()

        composeTestRule.runOnIdle { operations = emptyList() }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag(surface).assertDoesNotExist()
        composeTestRule.runOnIdle { (dismissals > 0) shouldBe true }
    }

    /**
     * The bar must only ask; it no longer owns the dialog, so opening one from inside it would put
     * it back at content bounds and content rank.
     */
    @Test
    fun `the operations bar only requests a cancel and opens no dialog itself`() {
        val op = operation()
        val requested = mutableListOf<Operation.Id>()

        composeTestRule.setContent {
            PreviewWrapper {
                PaneLayerHost(modifier = Modifier.fillMaxSize(), paneFocused = true) {
                    OperationsBar(
                        operations = listOf(op),
                        onRequestCancelOperation = { requested.add(it) },
                        onDismissOperation = {},
                        onOperationClick = {},
                        onClearCompleted = {},
                    )
                }
            }
        }

        composeTestRule.onNodeWithContentDescription(CANCEL_ENTRY_DESCRIPTION).performClick()

        composeTestRule.runOnIdle { requested shouldBe listOf(op.id) }
        composeTestRule.onNodeWithTag(surface).assertDoesNotExist()
    }

    companion object {
        /** `operations_cancel_operation` and the entry row's cancel affordance. */
        private const val CANCEL_OPERATION_ACTION = "Cancel operation"
        private const val CANCEL_ENTRY_DESCRIPTION = "Cancel operation"
    }
}
