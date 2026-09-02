package eu.darken.butler.workspace.ui.operations.details

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.Delete
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.workspace.core.operations.Operation
import eu.darken.butler.workspace.ui.modal.PaneLayerHost
import eu.darken.butler.workspace.ui.operations.OperationDisplay
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.Test
import org.robolectric.annotation.Config
import testhelpers.ComposeTest
import testhelpers.TestApplication
import kotlin.time.Clock

/**
 * The "Show in history" action addresses the operation's own history entry, which only exists for
 * operations the history records: a finished operation that has a [Operation.Metadata.Kind].
 */
@Config(application = TestApplication::class, sdk = [34], qualifiers = "w400dp-h1600dp")
class OperationHistoryActionTest : ComposeTest() {

    private val label = "Show in history"

    private fun operation(
        id: Operation.Id = Operation.Id(),
        kind: Operation.Metadata.Kind? = Operation.Metadata.Kind.DELETE,
        state: OperationDisplay.State = OperationDisplay.State.Completed(
            summary = "Deleted 3 items".toCaString(),
            completedAt = Clock.System.now(),
            report = null,
        ),
    ) = OperationDisplay(
        id = id,
        startedAt = Clock.System.now(),
        icon = Icons.TwoTone.Delete,
        title = "Deleting files".toCaString(),
        description = "3 items".toCaString(),
        kind = kind,
        state = state,
    )

    private fun setHost(
        operation: OperationDisplay,
        onShowInHistory: ((Operation.Id) -> Unit)?,
        historyEnabled: Boolean = true,
        onDismiss: () -> Unit = {},
    ) {
        composeTestRule.setContent {
            PreviewWrapper {
                PaneLayerHost(modifier = Modifier.fillMaxSize(), paneFocused = true) {
                    OperationDialogHost(
                        dialogState = OperationDialogState.OperationDetails(operation.id),
                        operations = listOf(operation),
                        onDismissDialog = onDismiss,
                        onShowInHistory = onShowInHistory,
                        historyEnabled = historyEnabled,
                    )
                }
            }
        }
    }

    @Test
    fun `finished operation offers the action and dismisses the sheet`() {
        val operation = operation()
        val shown = mutableListOf<Operation.Id>()
        var dismissed = 0
        setHost(operation, onShowInHistory = { shown.add(it) }, onDismiss = { dismissed++ })

        composeTestRule.onNodeWithText(label).assertIsDisplayed().performClick()

        shown shouldContainExactly listOf(operation.id)
        dismissed shouldBe 1
    }

    @Test
    fun `an operation the history does not record has no action`() {
        setHost(operation(kind = null), onShowInHistory = {})

        composeTestRule.onNodeWithText(label).assertDoesNotExist()
    }

    @Test
    fun `a running operation has no action`() {
        setHost(
            operation(state = OperationDisplay.State.Running()),
            onShowInHistory = {},
        )

        composeTestRule.onNodeWithText(label).assertDoesNotExist()
    }

    @Test
    fun `no action while the history is not recording`() {
        setHost(operation(), onShowInHistory = {}, historyEnabled = false)

        composeTestRule.onNodeWithText(label).assertDoesNotExist()
    }

    @Test
    fun `a host that cannot show history offers no action`() {
        setHost(operation(), onShowInHistory = null)

        composeTestRule.onNodeWithText(label).assertDoesNotExist()
    }
}
