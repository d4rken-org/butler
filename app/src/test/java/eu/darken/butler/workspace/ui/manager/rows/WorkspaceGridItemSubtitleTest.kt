package eu.darken.butler.workspace.ui.manager.rows

import android.content.Context
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import eu.darken.butler.common.ca.CaString
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.label
import eu.darken.butler.workspace.ui.manager.WorkspaceManagerViewModel
import org.junit.Test
import sh.calvin.reorderable.DragGestureDetector
import sh.calvin.reorderable.ReorderableCollectionItemScope
import testhelpers.ComposeTest

/**
 * The manager grid is the screen used to pick which paused tab to restore, so it must show the
 * identity — and must not draw an empty second line for workspaces that publish a blank subtitle.
 */
class WorkspaceGridItemSubtitleTest : ComposeTest() {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    private fun setContent(subtitle: CaString?, title: CaString = "/sdcard/Download".toCaString()) {
        composeTestRule.setContent {
            PreviewWrapper {
                WorkspaceGridItem(
                    reorderableScope = TestReorderableScope,
                    workspace = WorkspaceManagerViewModel.WorkspaceItem(
                        id = Workspace.Id(),
                        type = Workspace.Type.EXPLORER,
                        title = title,
                        subtitle = subtitle,
                    ),
                    onClose = {},
                    onSelect = {},
                    livePreview = false,
                )
            }
        }
    }

    @Test
    fun `renders the subtitle below the title`() {
        setContent("Storage".toCaString())

        composeTestRule.onNodeWithText("/sdcard/Download").assertIsDisplayed()
        composeTestRule.onNodeWithText("Storage").assertIsDisplayed()
    }

    @Test
    fun `a missing subtitle still renders the title`() {
        setContent(null)

        composeTestRule.onNodeWithText("/sdcard/Download").assertIsDisplayed()
    }

    @Test
    fun `an empty subtitle draws no second line`() {
        setContent("".toCaString())

        composeTestRule.onNodeWithText("/sdcard/Download").assertIsDisplayed()
        composeTestRule.onNodeWithText("").assertDoesNotExist()
    }

    @Test
    fun `a whitespace-only subtitle draws no second line`() {
        setContent("   ".toCaString())

        composeTestRule.onNodeWithText("/sdcard/Download").assertIsDisplayed()
        composeTestRule.onNodeWithText("   ").assertDoesNotExist()
    }

    @Test
    fun `a blank title falls back to the workspace type`() {
        setContent(subtitle = null, title = "  ".toCaString())

        // A nameless card cannot be picked from the manager, so the type stands in
        composeTestRule
            .onNodeWithText(Workspace.Type.EXPLORER.label.get(context))
            .assertIsDisplayed()
    }
}

private object TestReorderableScope : ReorderableCollectionItemScope {
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
