package eu.darken.butler.workspace.ui.manager.rows

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.compose.tour.LocalTourTargetRegistry
import eu.darken.butler.common.compose.tour.TourTargetRegistry
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.ui.manager.WorkspaceManagerViewModel
import eu.darken.butler.workspace.ui.manager.tour.WorkspaceManagerTour
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.Test
import sh.calvin.reorderable.DragGestureDetector
import sh.calvin.reorderable.ReorderableCollectionItemScope
import testhelpers.ComposeTest

/**
 * The two card gestures live on different halves of the same card, so the tour's cutouts have to
 * land on different rects: the title row it reorders from, the preview it starts a selection from.
 */
class WorkspaceGridItemTourAnchorTest : ComposeTest() {

    private val noopReorderableScope = object : ReorderableCollectionItemScope {
        override fun Modifier.draggableHandle(
            enabled: Boolean,
            interactionSource: MutableInteractionSource?,
            onDragStarted: (Offset) -> Unit,
            onDragStopped: () -> Unit,
            dragGestureDetector: DragGestureDetector,
        ): Modifier = this

        override fun Modifier.longPressDraggableHandle(
            enabled: Boolean,
            interactionSource: MutableInteractionSource?,
            onDragStarted: (Offset) -> Unit,
            onDragStopped: () -> Unit,
        ): Modifier = this
    }

    private fun item(): WorkspaceManagerViewModel.WorkspaceItem {
        val id = Workspace.Id()
        return WorkspaceManagerViewModel.WorkspaceItem(
            id = id,
            topId = id,
            type = Workspace.Type.EXPLORER,
            title = "/sdcard/Download".toCaString(),
            autoTitle = "/sdcard/Download".toCaString(),
            subtitle = null,
        )
    }

    private fun renderCard(isTourAnchor: Boolean): TourTargetRegistry {
        val registry = TourTargetRegistry()
        composeTestRule.setContent {
            CompositionLocalProvider(LocalTourTargetRegistry provides registry) {
                PreviewWrapper {
                    WorkspaceGridItem(
                        reorderableScope = noopReorderableScope,
                        workspace = item(),
                        onClose = {},
                        onSelect = {},
                        livePreview = false,
                        isTourAnchor = isTourAnchor,
                    )
                }
            }
        }
        composeTestRule.waitForIdle()
        return registry
    }

    @Test
    fun `the anchor card registers the header above the preview`() {
        val registry = renderCard(isTourAnchor = true)

        val header = registry.get(WorkspaceManagerTour.REORDER_TARGET)
        val preview = registry.get(WorkspaceManagerTour.SELECT_TARGET)
        header shouldNotBe null
        preview shouldNotBe null
        header shouldNotBe preview
        // Robolectric measures text badly, so only the ordering is asserted, never a pixel value.
        (header!!.top < preview!!.top) shouldBe true
    }

    @Test
    fun `a card that is not the anchor registers nothing`() {
        val registry = renderCard(isTourAnchor = false)

        registry.get(WorkspaceManagerTour.REORDER_TARGET) shouldBe null
        registry.get(WorkspaceManagerTour.SELECT_TARGET) shouldBe null
    }
}
