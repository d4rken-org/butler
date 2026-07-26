package eu.darken.butler.editor.ui.editor

import android.content.Context
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import eu.darken.butler.common.ca.caString
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.editor.R
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.ui.modal.PaneLayerHost
import kotlinx.coroutines.flow.flowOf
import org.junit.Test
import testhelpers.ComposeTest

/** The editor page's dialogs render from the overlay slot, not from the page. */
class EditorWorkspaceOverlaysTest : ComposeTest() {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun state(
        showCloseConfirmDialog: Boolean = false,
    ) = EditorWorkspaceViewModel.State(
        id = Workspace.Id(),
        title = caString("test.txt"),
        subTitle = caString("/storage/emulated/0/test.txt"),
        showCloseConfirmDialog = showCloseConfirmDialog,
    )

    @Test
    fun `nothing renders while no dialog is requested`() {
        composeTestRule.setContent {
            PreviewWrapper {
                PaneLayerHost(modifier = Modifier.fillMaxSize(), paneFocused = true) {
                    EditorWorkspaceOverlays(stateSource = flowOf(state()), onPageAction = {})
                }
            }
        }

        composeTestRule
            .onNodeWithText(context.getString(R.string.editor_dialog_close_confirm_title))
            .assertDoesNotExist()
    }

    @Test
    fun `the close confirmation renders from the overlay slot`() {
        composeTestRule.setContent {
            PreviewWrapper {
                PaneLayerHost(modifier = Modifier.fillMaxSize(), paneFocused = true) {
                    EditorWorkspaceOverlays(
                        stateSource = flowOf(state(showCloseConfirmDialog = true)),
                        onPageAction = {},
                    )
                }
            }
        }

        composeTestRule
            .onNodeWithText(context.getString(R.string.editor_dialog_close_confirm_title))
            .assertIsDisplayed()
    }
}
