package eu.darken.butler.editor.ui.editor

import android.content.Context
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ApplicationProvider
import eu.darken.butler.common.ca.caString
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.text.TextCharsets
import eu.darken.butler.editor.R
import eu.darken.butler.editor.core.engine.ContentSource
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.ui.modal.PaneLayerHost
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.flowOf
import org.junit.Test
import testhelpers.ComposeTest

/** The editor page's dialogs render from the overlay slot, not from the page. */
class EditorWorkspaceOverlaysTest : ComposeTest() {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun state(
        showCloseConfirmDialog: Boolean = false,
        showEncodingDialog: Boolean = false,
        showConvertEncodingDialog: Boolean = false,
        canWrite: Boolean = true,
    ) = EditorWorkspaceViewModel.State(
        id = Workspace.Id(),
        contentSource = ContentSource.File(
            path = LocalPath.build("/storage/emulated/0/test.txt"),
            size = 10L,
            lastModified = null,
            canWrite = canWrite,
            detectedCharset = TextCharsets.GB18030,
        ),
        title = caString("test.txt"),
        subTitle = caString("/storage/emulated/0/test.txt"),
        showCloseConfirmDialog = showCloseConfirmDialog,
        showEncodingDialog = showEncodingDialog,
        showConvertEncodingDialog = showConvertEncodingDialog,
    )

    private fun setOverlays(state: EditorWorkspaceViewModel.State, onPageAction: (EditorPageAction) -> Unit = {}) {
        composeTestRule.setContent {
            PreviewWrapper {
                PaneLayerHost(modifier = Modifier.fillMaxSize(), paneFocused = true) {
                    EditorWorkspaceOverlays(stateSource = flowOf(state), onPageAction = onPageAction)
                }
            }
        }
    }

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

    @Test
    fun `the encoding dialog offers conversion for a writable file`() {
        val actions = mutableListOf<EditorPageAction>()
        setOverlays(state(showEncodingDialog = true), onPageAction = { actions += it })

        composeTestRule
            .onNodeWithText(context.getString(R.string.editor_convert_encoding_action))
            // The action row may sit below the clipped dialog body on Robolectric; skip hit-testing
            .performSemanticsAction(SemanticsActions.OnClick)

        actions shouldBe listOf(EditorPageAction.File.ShowConvertEncodingPicker)
    }

    @Test
    fun `the encoding dialog hides conversion for a read-only file`() {
        setOverlays(state(showEncodingDialog = true, canWrite = false))

        composeTestRule
            .onNodeWithText(context.getString(R.string.editor_encoding_dialog_title))
            .assertIsDisplayed()
        composeTestRule
            .onNodeWithText(context.getString(R.string.editor_convert_encoding_action))
            .assertDoesNotExist()
    }

    @Test
    fun `the conversion picker reports the chosen charset`() {
        val actions = mutableListOf<EditorPageAction>()
        setOverlays(state(showConvertEncodingDialog = true), onPageAction = { actions += it })

        composeTestRule
            .onNodeWithText(context.getString(R.string.editor_convert_encoding_dialog_title))
            .assertIsDisplayed()
        composeTestRule.onNodeWithText("UTF-8").performClick()

        actions shouldBe listOf(EditorPageAction.File.ConvertEncoding("UTF-8"))
    }
}
