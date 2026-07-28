package eu.darken.butler.viewer.ui.viewer

import android.content.Context
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.MimeInfo
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
        var openWithCount = 0
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
                    onOpenWith = { openWithCount++ },
                )
            }
        }

        composeTestRule
            .onNodeWithContentDescription(context.getString(R.string.viewer_open_with_action))
            .performClick()

        openWithCount shouldBe 1
    }
}
