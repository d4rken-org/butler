package eu.darken.butler.viewer.ui.viewer

import android.content.Context
import android.graphics.Bitmap
import android.view.KeyEvent as NativeKeyEvent
import androidx.compose.animation.core.snap
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyPress
import androidx.test.core.app.ApplicationProvider
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.MimeInfo
import eu.darken.butler.viewer.R
import eu.darken.butler.viewer.core.ViewerContent
import eu.darken.butler.viewer.core.ViewerFileInfo
import eu.darken.butler.viewer.core.ViewerSource
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.ui.LocalWorkspaceFocused
import eu.darken.butler.workspace.ui.manager.WorkspaceDesign
import io.kotest.matchers.floats.shouldBeGreaterThan
import io.kotest.matchers.floats.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import me.saket.telephoto.zoomable.ZoomableState
import org.junit.Test
import org.robolectric.annotation.Config
import testhelpers.ComposeTest

@Config(qualifiers = "w400dp-h800dp")
class ViewerZoomableKeyboardTest : ComposeTest() {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    private val current = LocalPath.build("/storage/emulated/0/DCIM/b.jpg")

    private val actions = mutableListOf<ViewerActionBarItem>()
    private lateinit var zoomableState: ZoomableState
    private lateinit var scope: CoroutineScope

    private val state = ViewerWorkspaceViewModel.State.Ready(
        content = ViewerContent.Image(MimeInfo("image/jpeg")),
        fileInfo = ViewerFileInfo(size = 1024L),
        source = ViewerSource.Stored(current),
        imageSource = BitmapZoomableImageSource(Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)),
        neighbours = ViewerNeighbours(
            current = current,
            previous = LocalPath.build("/storage/emulated/0/DCIM/a.jpg"),
            next = LocalPath.build("/storage/emulated/0/DCIM/c.jpg"),
        ),
    )

    private val imageDescription = context.getString(R.string.viewer_image_content_description, "b.jpg")

    private fun setPage() {
        composeTestRule.setContent {
            PreviewWrapper {
                CompositionLocalProvider(LocalWorkspaceFocused provides true) {
                    zoomableState = rememberViewerZoomableState()
                    scope = rememberCoroutineScope()
                    ViewerWorkspacePage(
                        workspaceId = Workspace.Id(),
                        // Split-pane layout: keeps the mascot-bearing workspace button, which
                        // Robolectric cannot rasterise, out of the toolbar cutout.
                        design = WorkspaceDesign(layout = WorkspaceDesign.Layout.DUAL_VERTICAL),
                        state = state,
                        zoomableState = zoomableState,
                        onAction = { actions.add(it) },
                    )
                }
            }
        }
        composeTestRule.waitForIdle()
    }

    private fun press(keyCode: Int, metaState: Int = 0) {
        listOf(NativeKeyEvent.ACTION_DOWN, NativeKeyEvent.ACTION_UP).forEach { action ->
            composeTestRule.onRoot().performKeyPress(
                KeyEvent(NativeKeyEvent(0L, 0L, action, keyCode, 0, metaState)),
            )
        }
        composeTestRule.waitForIdle()
    }

    private val userZoom: Float
        get() = zoomableState.contentTransformation.scaleMetadata.userZoom

    /**
     * Telephoto puts its zoomable, and the focusable that comes with it, on the picture's own Image
     * node. The one focused node of the unmerged tree carrying that node's description and image
     * role is therefore the zoomable surface - not the page root, not a bar button, and not the
     * wrapper telephoto forwards focus through, which has neither.
     */
    private fun tabIntoImage() {
        press(NativeKeyEvent.KEYCODE_TAB)
        val focused = composeTestRule.onNode(isFocused(), useUnmergedTree = true).fetchSemanticsNode().config
        focused.getOrNull(SemanticsProperties.ContentDescription) shouldBe listOf(imageDescription)
        focused.getOrNull(SemanticsProperties.Role) shouldBe Role.Image
    }

    @Test
    fun `right steps to the next file while the picture holds focus`() {
        setPage()
        tabIntoImage()

        press(NativeKeyEvent.KEYCODE_DPAD_RIGHT)
        press(NativeKeyEvent.KEYCODE_DPAD_LEFT)

        actions shouldBe listOf(
            ViewerActionBarItem.NextFile(isEnabled = true),
            ViewerActionBarItem.PreviousFile(isEnabled = true),
        )
    }

    @Test
    fun `right pans a zoomed-in picture instead of stepping`() {
        setPage()
        tabIntoImage()

        press(NativeKeyEvent.KEYCODE_EQUALS, NativeKeyEvent.META_CTRL_ON)
        userZoom shouldBeGreaterThan 1f
        val offsetBefore = zoomableState.contentTransformation.offset

        press(NativeKeyEvent.KEYCODE_DPAD_RIGHT)

        actions shouldBe emptyList()
        zoomableState.contentTransformation.offset shouldNotBe offsetBefore
    }

    @Test
    fun `arrow keys still step files at a zoom the page treats as fit`() {
        setPage()
        tabIntoImage()

        scope.launch { zoomableState.zoomBy(zoomFactor = 1.005f, animationSpec = snap()) }
        composeTestRule.waitForIdle()
        userZoom shouldBeGreaterThan 1f
        userZoom shouldBeLessThanOrEqual ZOOM_COLLAPSE_THRESHOLD

        press(NativeKeyEvent.KEYCODE_DPAD_RIGHT)

        actions shouldBe listOf(ViewerActionBarItem.NextFile(isEnabled = true))
    }
}
