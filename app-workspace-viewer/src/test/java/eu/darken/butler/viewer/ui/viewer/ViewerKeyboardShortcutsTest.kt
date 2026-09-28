package eu.darken.butler.viewer.ui.viewer

import android.content.Context
import android.view.KeyEvent as NativeKeyEvent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyPress
import androidx.core.net.toUri
import androidx.test.core.app.ApplicationProvider
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.MimeInfo
import eu.darken.butler.viewer.core.ViewerContent
import eu.darken.butler.viewer.core.ViewerFileInfo
import eu.darken.butler.viewer.core.ViewerSource
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.ui.LocalWorkspaceFocused
import eu.darken.butler.workspace.ui.dialogs.DeleteConfirmationDialog
import eu.darken.butler.workspace.ui.manager.WorkspaceDesign
import eu.darken.butler.workspace.ui.modal.LocalPaneLayerRank
import eu.darken.butler.workspace.ui.modal.PaneLayer
import eu.darken.butler.workspace.ui.modal.PaneLayerHost
import eu.darken.butler.workspace.ui.modal.PaneLayerRank
import io.kotest.matchers.shouldBe
import org.junit.Test
import org.robolectric.annotation.Config
import testhelpers.ComposeTest
import eu.darken.butler.common.R as CommonR
import eu.darken.butler.workspace.R as WorkspaceR

@Config(qualifiers = "w400dp-h800dp")
class ViewerKeyboardShortcutsTest : ComposeTest() {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    private val previous = LocalPath.build("/storage/emulated/0/DCIM/a.jpg")
    private val current = LocalPath.build("/storage/emulated/0/DCIM/b.jpg")
    private val next = LocalPath.build("/storage/emulated/0/DCIM/c.jpg")

    private val actions = mutableListOf<ViewerActionBarItem>()
    private val pageActions = mutableListOf<ViewerPageAction>()
    private val pdfSteps = mutableListOf<String>()
    private var rootFocused = false
    private var rootEverFocused = false

    private fun imageState(
        neighbours: ViewerNeighbours? = ViewerNeighbours(current = current, previous = previous, next = next),
    ) = ViewerWorkspaceViewModel.State.Ready(
        content = ViewerContent.Image(MimeInfo("image/jpeg")),
        fileInfo = ViewerFileInfo(size = 1024L),
        source = ViewerSource.Stored(current),
        imageSource = null,
        neighbours = neighbours,
    )

    private val manual = LocalPath.build("/storage/emulated/0/Download/manual.pdf")

    private fun pdfState(
        neighbours: ViewerNeighbours? = null,
    ) = ViewerWorkspaceViewModel.State.Ready(
        content = ViewerContent.PdfPreview(MimeInfo("application/pdf"), pageCount = 3),
        fileInfo = ViewerFileInfo(size = 1024L),
        source = ViewerSource.Stored(manual),
        imageSource = null,
        neighbours = neighbours,
    )

    private fun streamedState(): ViewerWorkspaceViewModel.State.Ready {
        val streamed = ViewerSource.Streamed(
            uri = "content://com.example.files/document/42".toUri(),
            displayName = "holiday.jpg",
            mime = MimeInfo("image/jpeg"),
            sizeBytes = 1024L,
            arrivalId = "arrival-1",
        )
        return ViewerWorkspaceViewModel.State.Ready(
            content = ViewerContent.Image(MimeInfo("image/jpeg")),
            fileInfo = ViewerFileInfo(size = 1024L),
            source = streamed,
            imageSource = null,
            actions = viewerActions(streamed, trashEnabled = false),
        )
    }

    @Composable
    private fun Page(
        state: ViewerWorkspaceViewModel.State,
        onAction: (ViewerActionBarItem) -> Unit = { actions.add(it) },
    ) {
        ViewerWorkspacePage(
            modifier = Modifier.onFocusChanged {
                rootFocused = it.hasFocus
                if (it.hasFocus) rootEverFocused = true
            },
            workspaceId = Workspace.Id(),
            // Split-pane layout: keeps the mascot-bearing workspace button, which Robolectric
            // cannot rasterise, out of the toolbar cutout.
            design = WorkspaceDesign(layout = WorkspaceDesign.Layout.DUAL_VERTICAL),
            state = state,
            onAction = onAction,
            onPdfPreviousPage = { pdfSteps.add("previous") },
            onPdfNextPage = { pdfSteps.add("next") },
            onPageAction = { pageActions.add(it) },
        )
    }

    private fun setPage(state: ViewerWorkspaceViewModel.State, workspaceFocused: Boolean = true) {
        composeTestRule.setContent {
            PreviewWrapper {
                CompositionLocalProvider(LocalWorkspaceFocused provides workspaceFocused) {
                    Page(state)
                }
            }
        }
        composeTestRule.waitForIdle()
    }

    /** Presses and releases [keyCode] through whatever node holds focus. */
    private fun press(keyCode: Int, metaState: Int = 0) {
        listOf(NativeKeyEvent.ACTION_DOWN, NativeKeyEvent.ACTION_UP).forEach { action ->
            composeTestRule.onRoot().performKeyPress(
                KeyEvent(NativeKeyEvent(0L, 0L, action, keyCode, 0, metaState)),
            )
        }
        composeTestRule.waitForIdle()
    }

    private fun assertNothingDispatched() {
        actions shouldBe emptyList()
        pageActions shouldBe emptyList()
        pdfSteps shouldBe emptyList()
    }

    @Test
    fun `right and left step through the listing`() {
        setPage(imageState())
        rootFocused shouldBe true

        press(NativeKeyEvent.KEYCODE_DPAD_RIGHT)
        press(NativeKeyEvent.KEYCODE_DPAD_LEFT)

        actions shouldBe listOf(
            ViewerActionBarItem.NextFile(isEnabled = true),
            ViewerActionBarItem.PreviousFile(isEnabled = true),
        )
    }

    @Test
    fun `a disabled neighbour does not step`() {
        setPage(imageState(neighbours = ViewerNeighbours(current = current, previous = previous, next = null)))

        press(NativeKeyEvent.KEYCODE_DPAD_RIGHT)
        actions shouldBe emptyList()

        press(NativeKeyEvent.KEYCODE_DPAD_LEFT)
        actions shouldBe listOf(ViewerActionBarItem.PreviousFile(isEnabled = true))
    }

    @Test
    fun `a viewer without a listing does not step`() {
        setPage(imageState(neighbours = null))

        press(NativeKeyEvent.KEYCODE_DPAD_RIGHT)
        press(NativeKeyEvent.KEYCODE_DPAD_LEFT)

        assertNothingDispatched()
    }

    @Test
    fun `delete asks to delete a stored file`() {
        setPage(imageState())

        press(NativeKeyEvent.KEYCODE_FORWARD_DEL)

        actions shouldBe listOf(ViewerActionBarItem.Delete(trashEnabled = false))
        pageActions shouldBe emptyList()
    }

    @Test
    fun `delete does nothing for streamed content`() {
        setPage(streamedState())

        press(NativeKeyEvent.KEYCODE_FORWARD_DEL)
        press(NativeKeyEvent.KEYCODE_FORWARD_DEL, NativeKeyEvent.META_SHIFT_ON)

        assertNothingDispatched()
    }

    @Test
    fun `shift+delete asks for a permanent delete`() {
        setPage(imageState())

        press(NativeKeyEvent.KEYCODE_FORWARD_DEL, NativeKeyEvent.META_SHIFT_ON)

        pageActions shouldBe listOf(ViewerPageAction.RequestPermanentDelete)
        actions shouldBe emptyList()
    }

    @Test
    fun `page down and page up turn pdf pages`() {
        setPage(pdfState())

        press(NativeKeyEvent.KEYCODE_PAGE_DOWN)
        press(NativeKeyEvent.KEYCODE_PAGE_UP)

        pdfSteps shouldBe listOf("next", "previous")
    }

    @Test
    fun `page down does nothing outside a pdf`() {
        setPage(imageState())

        press(NativeKeyEvent.KEYCODE_PAGE_DOWN)
        press(NativeKeyEvent.KEYCODE_PAGE_UP)

        assertNothingDispatched()
    }

    @Test
    fun `an unfocused workspace neither requests focus nor answers keys`() {
        // A PDF inside a listing, so every shortcut would answer if the workspace were focused.
        setPage(
            pdfState(neighbours = ViewerNeighbours(current = manual, previous = previous, next = next)),
            workspaceFocused = false,
        )
        rootEverFocused shouldBe false

        press(NativeKeyEvent.KEYCODE_FORWARD_DEL)
        press(NativeKeyEvent.KEYCODE_FORWARD_DEL, NativeKeyEvent.META_SHIFT_ON)
        rootEverFocused shouldBe false

        // Page down and the arrows come last: unconsumed, they drive focus traversal, which may move focus in.
        press(NativeKeyEvent.KEYCODE_PAGE_DOWN)
        press(NativeKeyEvent.KEYCODE_DPAD_RIGHT)
        press(NativeKeyEvent.KEYCODE_DPAD_LEFT)

        assertNothingDispatched()
    }

    @Test
    fun `a workspace that loses focus stops answering keys while its root still holds focus`() {
        var workspaceFocused by mutableStateOf(true)
        composeTestRule.setContent {
            PreviewWrapper {
                CompositionLocalProvider(LocalWorkspaceFocused provides workspaceFocused) {
                    Page(imageState())
                }
            }
        }
        composeTestRule.waitForIdle()
        rootFocused shouldBe true

        workspaceFocused = false
        composeTestRule.waitForIdle()
        rootFocused shouldBe true

        press(NativeKeyEvent.KEYCODE_FORWARD_DEL)
        press(NativeKeyEvent.KEYCODE_DPAD_RIGHT)

        assertNothingDispatched()
    }

    @Test
    fun `keys stay with the delete dialog and come back to the viewer once it is dismissed`() {
        var dialogShown by mutableStateOf(false)
        composeTestRule.setContent {
            PreviewWrapper {
                PaneLayerHost(modifier = Modifier.fillMaxSize(), paneFocused = true) {
                    PaneLayer(
                        modifier = Modifier.fillMaxSize(),
                        rank = PaneLayerRank.contentAt(0),
                        modal = false,
                    ) {
                        Page(
                            state = imageState(),
                            onAction = {
                                actions.add(it)
                                if (it is ViewerActionBarItem.Delete) dialogShown = true
                            },
                        )
                    }
                    if (dialogShown) {
                        CompositionLocalProvider(LocalPaneLayerRank provides PaneLayerRank.overlayAt(0)) {
                            DeleteConfirmationDialog(
                                items = setOf(current),
                                trashEnabled = false,
                                onDismiss = { dialogShown = false },
                                onConfirm = { _, _ -> },
                            )
                        }
                    }
                }
            }
        }
        composeTestRule.waitForIdle()

        press(NativeKeyEvent.KEYCODE_FORWARD_DEL)
        dialogShown shouldBe true
        actions shouldBe listOf(ViewerActionBarItem.Delete(trashEnabled = false))
        val confirm = context.getString(WorkspaceR.string.workspace_dialog_delete_permanently_action)
        val cancel = context.getString(CommonR.string.general_cancel_action)
        composeTestRule.onNodeWithText(confirm).assertIsFocused()

        press(NativeKeyEvent.KEYCODE_FORWARD_DEL)
        press(NativeKeyEvent.KEYCODE_DPAD_RIGHT)
        press(NativeKeyEvent.KEYCODE_DPAD_LEFT)
        actions shouldBe listOf(ViewerActionBarItem.Delete(trashEnabled = false))
        pageActions shouldBe emptyList()

        // The left arrow above already moved focus from the confirm button onto Cancel.
        composeTestRule.onNodeWithText(cancel).assertIsFocused()
        press(NativeKeyEvent.KEYCODE_ENTER)
        dialogShown shouldBe false
        rootFocused shouldBe true

        press(NativeKeyEvent.KEYCODE_DPAD_RIGHT)
        actions shouldBe listOf(
            ViewerActionBarItem.Delete(trashEnabled = false),
            ViewerActionBarItem.NextFile(isEnabled = true),
        )
    }
}
