package eu.darken.butler.explorer.ui.explorer.dialogs

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.workspace.contracts.dnd.WorkspaceDragPayload
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.ui.modal.PaneLayerHost
import io.kotest.matchers.shouldBe
import org.junit.Test
import testhelpers.ComposeTest

class DropConfirmationDialogTest : ComposeTest() {

    private val destination = LocalPath.build("/storage/emulated/0/Download")

    private fun payload(allowMove: Boolean) = WorkspaceDragPayload(
        sourceWorkspaceId = Workspace.Id(),
        items = listOf(
            WorkspaceDragPayload.Item(
                path = LocalPath.build("/storage/emulated/0/DCIM/photo.jpg"),
                kind = WorkspaceDragPayload.Kind.FILE_OTHER,
            ),
        ),
        allowMove = allowMove,
    )

    private fun setDialog(
        allowMove: Boolean = true,
        onDismiss: () -> Unit = {},
        onCopy: () -> Unit = {},
        onMove: () -> Unit = {},
    ) {
        composeTestRule.setContent {
            PreviewWrapper {
                PaneLayerHost(modifier = Modifier.fillMaxSize(), paneFocused = true) {
                    DropConfirmationDialog(
                        payload = payload(allowMove),
                        destination = destination,
                        onDismiss = onDismiss,
                        onCopy = onCopy,
                        onMove = onMove,
                    )
                }
            }
        }
    }

    @Test
    fun `copy, move and cancel are offered when moving is allowed`() {
        setDialog(allowMove = true)

        composeTestRule.onNodeWithText("Copy").assertIsDisplayed()
        composeTestRule.onNodeWithText("Move").assertIsDisplayed()
        composeTestRule.onNodeWithText("Cancel").assertIsDisplayed()
    }

    @Test
    fun `move is absent when the source does not allow it`() {
        setDialog(allowMove = false)

        composeTestRule.onNodeWithText("Copy").assertIsDisplayed()
        composeTestRule.onNodeWithText("Move").assertDoesNotExist()
    }

    @Test
    fun `copy reports a copy`() {
        var copied = 0
        setDialog(onCopy = { copied++ })

        composeTestRule.onNodeWithText("Copy").performClick()

        composeTestRule.runOnIdle { copied shouldBe 1 }
    }

    @Test
    fun `move reports a move`() {
        var moved = 0
        setDialog(onMove = { moved++ })

        composeTestRule.onNodeWithText("Move").performClick()

        composeTestRule.runOnIdle { moved shouldBe 1 }
    }

    @Test
    fun `cancel dismisses`() {
        var dismissed = 0
        setDialog(onDismiss = { dismissed++ })

        composeTestRule.onNodeWithText("Cancel").performClick()

        composeTestRule.runOnIdle { dismissed shouldBe 1 }
    }

    @Test
    fun `a second copy tap cannot reach a dismissed dialog`() {
        var copied = 0
        var visible by mutableStateOf(true)

        composeTestRule.setContent {
            PreviewWrapper {
                PaneLayerHost(modifier = Modifier.fillMaxSize(), paneFocused = true) {
                    if (visible) {
                        DropConfirmationDialog(
                            payload = payload(allowMove = true),
                            destination = destination,
                            onDismiss = { visible = false },
                            // Mirrors the ViewModel: the dialog is dismissed before the operation runs.
                            onCopy = {
                                copied++
                                visible = false
                            },
                            onMove = {},
                        )
                    }
                }
            }
        }

        composeTestRule.onNodeWithText("Copy").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Copy").assertDoesNotExist()
        composeTestRule.runOnIdle { copied shouldBe 1 }
    }
}
