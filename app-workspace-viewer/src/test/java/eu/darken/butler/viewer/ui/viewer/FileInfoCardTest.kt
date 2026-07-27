package eu.darken.butler.viewer.ui.viewer

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.MimeInfo
import eu.darken.butler.common.files.metadata.Ownership
import eu.darken.butler.common.files.metadata.Permissions
import eu.darken.butler.viewer.R
import eu.darken.butler.viewer.core.ViewerContent
import eu.darken.butler.viewer.core.ViewerFileInfo
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.ui.manager.WorkspaceDesign
import org.junit.Test
import testhelpers.ComposeTest

class FileInfoCardTest : ComposeTest() {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun `posix metadata is shown when the gateway provides it`() {
        composeTestRule.setContent {
            PreviewWrapper {
                FileInfoCard(
                    fileInfo = ViewerFileInfo(
                        size = 1024L,
                        permissions = Permissions(0b110_100_100),
                        ownership = Ownership(
                            userId = 1000L,
                            groupId = 1000L,
                            userName = "media_rw",
                            groupName = "media_rw",
                        ),
                    ),
                )
            }
        }

        composeTestRule
            .onNodeWithText(context.getString(R.string.viewer_info_permissions_label))
            .assertIsDisplayed()
        composeTestRule
            .onNodeWithText(context.getString(R.string.viewer_info_owner_label))
            .assertIsDisplayed()
    }

    @Test
    fun `permission and owner rows are absent when the gateway returns null`() {
        composeTestRule.setContent {
            PreviewWrapper {
                FileInfoCard(
                    fileInfo = ViewerFileInfo(
                        size = 1024L,
                        permissions = null,
                        ownership = null,
                    ),
                )
            }
        }

        composeTestRule
            .onNodeWithText(context.getString(R.string.viewer_info_permissions_label))
            .assertDoesNotExist()
        composeTestRule
            .onNodeWithText(context.getString(R.string.viewer_info_owner_label))
            .assertDoesNotExist()
    }

    @Test
    fun `image dimensions are only shown when both are known`() {
        composeTestRule.setContent {
            PreviewWrapper {
                FileInfoCard(
                    fileInfo = ViewerFileInfo(
                        imageInfo = ViewerFileInfo.ImageInfo(format = "image/svg+xml"),
                    ),
                )
            }
        }

        composeTestRule
            .onNodeWithText(context.getString(R.string.viewer_info_format_label))
            .assertIsDisplayed()
        composeTestRule
            .onNodeWithText(context.getString(R.string.viewer_info_dimensions_label))
            .assertDoesNotExist()
    }

    private val fileInfo = ViewerFileInfo(
        size = 1024L,
        imageInfo = ViewerFileInfo.ImageInfo(format = "image/jpeg", width = 4032, height = 3024),
        permissions = Permissions(0b110_100_100),
        ownership = Ownership(userId = 1000L, groupId = 1000L, userName = "media_rw", groupName = "media_rw"),
    )

    private fun pageWith(content: ViewerContent) {
        composeTestRule.setContent {
            PreviewWrapper {
                ViewerWorkspacePage(
                    workspaceId = Workspace.Id(),
                    // Split-pane layout: keeps the mascot-bearing workspace button, which
                    // Robolectric cannot rasterise, out of the toolbar cutout.
                    design = WorkspaceDesign(layout = WorkspaceDesign.Layout.DUAL_VERTICAL),
                    state = ViewerWorkspaceViewModel.State.Ready(
                        content = content,
                        fileInfo = fileInfo,
                        path = LocalPath.build("/storage/emulated/0/DCIM/photo.jpg"),
                        imageSource = null,
                    ),
                )
            }
        }
    }

    @Test
    fun `the metadata card is shown while the image is fine`() {
        pageWith(ViewerContent.Image(MimeInfo("image/jpeg")))

        // Only existence: the card rides a sliding floating bar, whose on-screen position is not
        // something Robolectric's layout pass can be trusted for.
        composeTestRule
            .onNodeWithText(context.getString(R.string.viewer_info_size_label))
            .assertExists()
    }

    @Test
    fun `the metadata card is gone once the content failed`() {
        // The numbers describe the file as it was before it vanished - showing them next to the
        // error would present stale data as current.
        pageWith(ViewerContent.Failed(IllegalStateException("gone")))

        composeTestRule
            .onNodeWithText(context.getString(R.string.viewer_info_size_label))
            .assertDoesNotExist()
        composeTestRule
            .onNodeWithText(context.getString(R.string.viewer_info_dimensions_label))
            .assertDoesNotExist()
        composeTestRule
            .onNodeWithText(context.getString(R.string.viewer_info_permissions_label))
            .assertDoesNotExist()
        composeTestRule
            .onNodeWithText(context.getString(R.string.viewer_info_owner_label))
            .assertDoesNotExist()
    }
}
