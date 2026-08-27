package eu.darken.butler.workspace.ui.dialogs

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.compose.PreviewWrapper
import io.kotest.matchers.shouldBe
import org.junit.Test
import testhelpers.ComposeTest

class WorkspaceCloseConfirmationDialogTest : ComposeTest() {

    private fun setDialog(
        hasUnsavedChanges: Boolean = false,
        unsavedCount: Int = 0,
        onConfirm: () -> Unit = {},
    ) {
        composeTestRule.setContent {
            PreviewWrapper {
                WorkspaceCloseConfirmationDialog(
                    workspaceTitle = "notes.txt".toCaString(),
                    hasUnsavedChanges = hasUnsavedChanges,
                    unsavedCount = unsavedCount,
                    onDismiss = {},
                    onConfirm = onConfirm,
                )
            }
        }
    }

    @Test
    fun `a clean tab gets the plain close copy`() {
        setDialog()

        composeTestRule.onNode(hasText("notes.txt", substring = true)).assertExists()
        composeTestRule.onAllNodesWithText("unsaved changes", substring = true).assertCountEquals(0)
        composeTestRule.onNodeWithText("Close").assertExists()
    }

    @Test
    fun `one unsaved member names it and offers to discard`() {
        setDialog(hasUnsavedChanges = true, unsavedCount = 1)

        composeTestRule
            .onNode(hasText("\"notes.txt\" has unsaved changes. Close and discard them?"))
            .assertExists()
        composeTestRule.onNodeWithText("Discard").assertExists()
    }

    /** Closing a tab discards its whole modal stack, so naming one member would understate it. */
    @Test
    fun `several unsaved members say how many more go down`() {
        setDialog(hasUnsavedChanges = true, unsavedCount = 3)

        composeTestRule
            .onNode(hasText("\"notes.txt\" and 2 more have unsaved changes. Close and discard them?"))
            .assertExists()
    }

    @Test
    fun `confirming reports once`() {
        var confirmed = 0
        setDialog(hasUnsavedChanges = true, unsavedCount = 1) { confirmed++ }

        composeTestRule.onNodeWithText("Discard").performClick()

        confirmed shouldBe 1
    }
}
