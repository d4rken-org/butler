package eu.darken.butler.workspace.ui.manager.rows

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.ui.manager.WorkspaceManagerViewModel
import io.kotest.matchers.shouldBe
import org.junit.Test
import sh.calvin.reorderable.DragGestureDetector
import sh.calvin.reorderable.ReorderableCollectionItemScope
import testhelpers.ComposeTest

class WorkspaceGridItemTest : ComposeTest() {

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

    private fun item(isSubWorkspace: Boolean = false) = WorkspaceManagerViewModel.WorkspaceItem(
        id = Workspace.Id(),
        type = Workspace.Type.EXPLORER,
        title = "Explorer".toCaString(),
        subtitle = null,
        isSubWorkspace = isSubWorkspace,
    )

    @Test
    fun `the overflow menu triggers rename without selecting or closing`() {
        var renamed = 0
        var selected = 0
        var closed = 0

        composeTestRule.setContent {
            PreviewWrapper {
                WorkspaceGridItem(
                    reorderableScope = noopReorderableScope,
                    workspace = item(),
                    onClose = { closed++ },
                    onSelect = { selected++ },
                    onRename = { renamed++ },
                    livePreview = false,
                )
            }
        }

        composeTestRule.onNodeWithContentDescription("More options").performClick()
        composeTestRule.onNodeWithText("Rename").performClick()

        renamed shouldBe 1
        selected shouldBe 0
        closed shouldBe 0
    }

    @Test
    fun `a sub-workspace card has no overflow menu`() {
        composeTestRule.setContent {
            PreviewWrapper {
                WorkspaceGridItem(
                    reorderableScope = noopReorderableScope,
                    workspace = item(isSubWorkspace = true),
                    onClose = {},
                    onSelect = {},
                    onRename = {},
                    livePreview = false,
                )
            }
        }

        composeTestRule.onAllNodesWithContentDescription("More options").assertCountEquals(0)
        composeTestRule.onNodeWithContentDescription("Close tab").assertExists()
    }
}
