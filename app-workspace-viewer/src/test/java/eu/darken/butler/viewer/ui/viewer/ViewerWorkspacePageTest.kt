package eu.darken.butler.viewer.ui.viewer

import android.content.Context
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.MimeInfo
import eu.darken.butler.common.files.SAFPath
import eu.darken.butler.viewer.R
import eu.darken.butler.viewer.core.ViewerContent
import eu.darken.butler.viewer.core.ViewerFileInfo
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.ui.manager.WorkspaceDesign
import io.kotest.matchers.shouldBe
import org.junit.Test
import testhelpers.ComposeTest

class ViewerWorkspacePageTest : ComposeTest() {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun `the action bar hands open-with back to the caller`() {
        val clicked = mutableListOf<ViewerActionBarItem>()
        composeTestRule.setContent {
            PreviewWrapper {
                ViewerWorkspacePage(
                    workspaceId = Workspace.Id(),
                    // Split-pane layout: keeps the mascot-bearing workspace button, which
                    // Robolectric cannot rasterise, out of the toolbar cutout.
                    design = WorkspaceDesign(layout = WorkspaceDesign.Layout.DUAL_VERTICAL),
                    state = ViewerWorkspaceViewModel.State.Ready(
                        content = ViewerContent.Image(MimeInfo("image/jpeg")),
                        fileInfo = ViewerFileInfo(size = 1024L),
                        path = LocalPath.build("/storage/emulated/0/DCIM/photo.jpg"),
                        imageSource = null,
                    ),
                    onAction = { clicked.add(it) },
                )
            }
        }

        composeTestRule
            .onNodeWithContentDescription(context.getString(R.string.viewer_open_with_action))
            .performClick()

        clicked shouldBe listOf(ViewerActionBarItem.OpenWith)
    }

    @Test
    fun `a file at a storage root cannot open its location`() {
        // LocalPath.build("/") has no parent, so there is no folder to show in an Explorer tab.
        val actions = viewerActions(path = LocalPath.build("/"), trashEnabled = false)

        actions.filterIsInstance<ViewerActionBarItem.OpenLocation>().single().isEnabled shouldBe false
        viewerActions(path = LocalPath.build("/storage/emulated/0/photo.jpg"), trashEnabled = false)
            .filterIsInstance<ViewerActionBarItem.OpenLocation>().single().isEnabled shouldBe true
    }

    @Test
    fun `delete is only destructive when it bypasses the trash`() {
        val path = LocalPath.build("/storage/emulated/0/photo.jpg")

        viewerActions(path, trashEnabled = true)
            .filterIsInstance<ViewerActionBarItem.Delete>().single().isDestructive shouldBe false
        viewerActions(path, trashEnabled = false)
            .filterIsInstance<ViewerActionBarItem.Delete>().single().isDestructive shouldBe true
    }

    @Test
    fun `a file the trash cannot hold reads as a permanent delete`() {
        // The trash only takes LocalPaths, so the setting being on says nothing about a SAF file.
        // The icon has to agree with the confirmation dialog, which asks the same shared function.
        val safPath = SAFPath.build(
            "content://com.android.externalstorage.documents/tree/primary%3ADownload",
            "photo.jpg",
        )

        viewerActions(safPath, trashEnabled = true)
            .filterIsInstance<ViewerActionBarItem.Delete>().single().isDestructive shouldBe true
    }

    private fun unsupportedState(content: ViewerContent) = ViewerWorkspaceViewModel.State.Ready(
        content = content,
        fileInfo = ViewerFileInfo(size = 1024L),
        path = LocalPath.build("/storage/emulated/0/Download/archive.zip"),
        imageSource = null,
    )

    // A hidden floating bar keeps its content composed - FloatingBarStack expresses visibility by
    // not placing the bar, never by dropping it. So displayed-ness is the assertion here; existence
    // would be true either way.
    private fun actionBar() = composeTestRule
        .onNodeWithContentDescription(context.getString(R.string.viewer_open_with_action))

    private fun tapContent() = composeTestRule
        .onNodeWithText(context.getString(R.string.viewer_unsupported_title))
        .performClick()

    @Test
    fun `tapping the content takes the chrome away and brings it back`() {
        composeTestRule.setContent {
            PreviewWrapper {
                ViewerWorkspacePage(
                    workspaceId = Workspace.Id(),
                    design = WorkspaceDesign(layout = WorkspaceDesign.Layout.DUAL_VERTICAL),
                    state = unsupportedState(ViewerContent.Unsupported(MimeInfo("application/zip"))),
                )
            }
        }

        // The action bar's icon carries the label as its content description; the placeholder's own
        // button carries it as text, so this matcher only ever sees the bar.
        actionBar().assertIsDisplayed()

        tapContent()
        actionBar().assertIsNotDisplayed()

        tapContent()
        actionBar().assertIsDisplayed()
    }

    @Test
    fun `a pdf whose page has not rendered keeps the chrome reachable`() {
        // Only a spinner is on screen in this state, so there is nothing to tap - a chrome hidden
        // before the render started would have no way back.
        composeTestRule.setContent {
            PreviewWrapper {
                ViewerWorkspacePage(
                    workspaceId = Workspace.Id(),
                    design = WorkspaceDesign(layout = WorkspaceDesign.Layout.DUAL_VERTICAL),
                    state = ViewerWorkspaceViewModel.State.Ready(
                        content = ViewerContent.PdfPreview(MimeInfo("application/pdf"), pageCount = 3),
                        fileInfo = ViewerFileInfo(size = 128_004L),
                        path = LocalPath.build("/storage/emulated/0/Download/manual.pdf"),
                        imageSource = null,
                        pdfPage = null,
                    ),
                    initiallyChromeVisible = false,
                )
            }
        }

        actionBar().assertIsDisplayed()
    }

    @Test
    fun `a page that failed to render keeps the chrome reachable`() {
        // Worse than a pending render: this one will not resolve on its own, and the failure card
        // is not a tap surface either.
        composeTestRule.setContent {
            PreviewWrapper {
                ViewerWorkspacePage(
                    workspaceId = Workspace.Id(),
                    design = WorkspaceDesign(layout = WorkspaceDesign.Layout.DUAL_VERTICAL),
                    state = ViewerWorkspaceViewModel.State.Ready(
                        content = ViewerContent.PdfPreview(MimeInfo("application/pdf"), pageCount = 3),
                        fileInfo = ViewerFileInfo(size = 128_004L),
                        path = LocalPath.build("/storage/emulated/0/Download/manual.pdf"),
                        imageSource = null,
                        pdfPage = ViewerWorkspaceViewModel.PdfPage(index = 1, bitmap = null, failed = true),
                    ),
                    initiallyChromeVisible = false,
                )
            }
        }

        actionBar().assertIsDisplayed()
    }

    @Test
    fun `a failure brings the chrome back after it was tapped away`() {
        // Retry and back live in the chrome, and an error card is not a surface to tap - hiding it
        // and then failing would strand the user.
        var content by mutableStateOf<ViewerContent>(ViewerContent.Unsupported(MimeInfo("application/zip")))
        composeTestRule.setContent {
            PreviewWrapper {
                ViewerWorkspacePage(
                    workspaceId = Workspace.Id(),
                    design = WorkspaceDesign(layout = WorkspaceDesign.Layout.DUAL_VERTICAL),
                    state = unsupportedState(content),
                )
            }
        }

        tapContent()
        actionBar().assertIsNotDisplayed()

        composeTestRule.runOnIdle { content = ViewerContent.Failed(IllegalStateException("gone")) }

        actionBar().assertIsDisplayed()
    }

    private val readyState = ViewerWorkspaceViewModel.State.Ready(
        content = ViewerContent.Image(MimeInfo("image/jpeg")),
        fileInfo = ViewerFileInfo(size = 1024L),
        path = LocalPath.build("/storage/emulated/0/DCIM/photo.jpg"),
        imageSource = null,
    )

    private val failedState = ViewerWorkspaceViewModel.State.Ready(
        content = ViewerContent.Failed(IllegalStateException("Decoder gave up")),
        fileInfo = null,
        path = LocalPath.build("/storage/emulated/0/DCIM/photo.jpg"),
        imageSource = null,
    )

    /**
     * A drill-down viewer is a pane-local overlay with no enclosing dialog to dismiss, so back has to
     * reach it in every phase - including while the file is still loading and after a decode failure.
     */
    private fun pressBack(
        state: ViewerWorkspaceViewModel.State,
        callerWorkspaceId: Workspace.Id?,
    ): Int {
        var closeCount = 0
        var dispatcher: OnBackPressedDispatcher? = null

        composeTestRule.setContent {
            dispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
            PreviewWrapper {
                ViewerWorkspacePage(
                    workspaceId = Workspace.Id(),
                    design = WorkspaceDesign(layout = WorkspaceDesign.Layout.DUAL_VERTICAL),
                    state = state,
                    callerWorkspaceId = callerWorkspaceId,
                    onPageAction = { action ->
                        when (action) {
                            ViewerPageAction.Close -> closeCount++
                        }
                    },
                )
            }
        }

        composeTestRule.runOnIdle { dispatcher!!.onBackPressed() }
        composeTestRule.waitForIdle()

        return closeCount
    }

    @Test
    fun `a pdf preview announces which page it shows instead of the unsupported notice`() {
        composeTestRule.setContent {
            PreviewWrapper {
                ViewerWorkspacePage(
                    workspaceId = Workspace.Id(),
                    design = WorkspaceDesign(layout = WorkspaceDesign.Layout.DUAL_VERTICAL),
                    state = ViewerWorkspaceViewModel.State.Ready(
                        content = ViewerContent.PdfPreview(MimeInfo("application/pdf"), pageCount = 3),
                        fileInfo = ViewerFileInfo(size = 128_004L),
                        path = LocalPath.build("/storage/emulated/0/Download/manual.pdf"),
                        imageSource = null,
                        // Robolectric cannot rasterise a bitmap, so the render stays pending here.
                        pdfPage = null,
                    ),
                )
            }
        }

        composeTestRule
            .onNodeWithText(context.getString(R.string.viewer_pdf_page_indicator, 1, 3))
            .assertIsDisplayed()
        composeTestRule
            .onNodeWithText(context.getString(R.string.viewer_unsupported_title))
            .assertDoesNotExist()
    }

    @Test
    fun `back closes a drill-down that is still loading`() {
        pressBack(ViewerWorkspaceViewModel.State.Initializing, callerWorkspaceId = Workspace.Id()) shouldBe 1
    }

    @Test
    fun `back closes a drill-down showing an image`() {
        pressBack(readyState, callerWorkspaceId = Workspace.Id()) shouldBe 1
    }

    @Test
    fun `back closes a drill-down that failed to decode`() {
        pressBack(failedState, callerWorkspaceId = Workspace.Id()) shouldBe 1
    }

    @Test
    fun `back leaves a viewer tab alone`() {
        pressBack(readyState, callerWorkspaceId = null) shouldBe 0
    }

    @Test
    fun `a drill-down offers a back affordance in its toolbar`() {
        var closeCount = 0
        composeTestRule.setContent {
            PreviewWrapper {
                ViewerWorkspacePage(
                    workspaceId = Workspace.Id(),
                    design = WorkspaceDesign(layout = WorkspaceDesign.Layout.DUAL_VERTICAL),
                    state = readyState,
                    callerWorkspaceId = Workspace.Id(),
                    onPageAction = { closeCount++ },
                )
            }
        }

        composeTestRule
            .onNodeWithContentDescription(context.getString(R.string.viewer_back_action))
            .performClick()

        closeCount shouldBe 1
    }
}
