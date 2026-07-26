package eu.darken.butler.workspace.ui.manager.rows

import android.content.Context
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
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
 * identity — the header naming the tab, the info bar describing what it holds — and must not draw
 * an empty line for a workspace that publishes a blank automatic title or subtitle.
 *
 * Assertions are anchored to the header and info bar test tags: text alone cannot tell the two
 * apart, so a regression swapping them would still pass. The card's Column is clickable and merges
 * descendant semantics, which is why these structural assertions read the unmerged tree.
 */
class WorkspaceGridItemIdentityTest : ComposeTest() {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    private fun setContent(
        subtitle: CaString?,
        autoTitle: CaString = "/sdcard/Download".toCaString(),
        customTitle: String? = null,
        isPaused: Boolean = false,
    ) {
        composeTestRule.setContent {
            PreviewWrapper {
                WorkspaceGridItem(
                    reorderableScope = TestReorderableScope,
                    workspace = WorkspaceManagerViewModel.WorkspaceItem(
                        id = Workspace.Id(),
                        type = Workspace.Type.EXPLORER,
                        title = customTitle?.toCaString() ?: autoTitle,
                        autoTitle = autoTitle,
                        subtitle = subtitle,
                        customTitle = customTitle,
                        isPaused = isPaused,
                    ),
                    onClose = {},
                    onSelect = {},
                    livePreview = false,
                )
            }
        }
    }

    private fun assertInHeader(text: String) = composeTestRule
        .onNode(
            hasText(text) and hasAnyAncestor(hasTestTag(TEST_TAG_WORKSPACE_CARD_HEADER)),
            useUnmergedTree = true,
        )
        .assertIsDisplayed()

    private fun assertInInfoBar(text: String) = composeTestRule
        .onNode(
            hasText(text) and hasAnyAncestor(hasTestTag(TEST_TAG_WORKSPACE_CARD_INFOBAR)),
            useUnmergedTree = true,
        )
        .assertIsDisplayed()

    @Test
    fun `the header names the workspace type when there is no custom name`() {
        setContent(subtitle = "Storage".toCaString())

        assertInHeader(Workspace.Type.EXPLORER.label.get(context))
    }

    @Test
    fun `a custom name takes the header while the automatic title stays in the info bar`() {
        setContent(subtitle = null, customTitle = "Holiday photos")

        assertInHeader("Holiday photos")
        assertInInfoBar("/sdcard/Download")
    }

    @Test
    fun `the info bar shows the automatic title and the subtitle together`() {
        setContent(subtitle = "Storage".toCaString())

        assertInInfoBar("/sdcard/Download")
        assertInInfoBar("Storage")
    }

    @Test
    fun `a blank automatic title leaves the subtitle as the only info bar line`() {
        setContent(subtitle = "Storage".toCaString(), autoTitle = "  ".toCaString())

        assertInInfoBar("Storage")
        composeTestRule.onNodeWithText("  ").assertDoesNotExist()
    }

    @Test
    fun `an empty subtitle draws no second info bar line`() {
        setContent(subtitle = "".toCaString())

        assertInInfoBar("/sdcard/Download")
        composeTestRule.onNodeWithText("").assertDoesNotExist()
    }

    @Test
    fun `a whitespace-only subtitle draws no second info bar line`() {
        setContent(subtitle = "   ".toCaString())

        assertInInfoBar("/sdcard/Download")
        composeTestRule.onNodeWithText("   ").assertDoesNotExist()
    }

    @Test
    fun `a paused workspace keeps its info bar - that is what tells you whether to resume it`() {
        setContent(subtitle = "Storage".toCaString(), isPaused = true)

        assertInInfoBar("/sdcard/Download")
        assertInInfoBar("Storage")
    }

    @Test
    fun `a workspace with nothing to say draws no info bar at all`() {
        setContent(subtitle = "   ".toCaString(), autoTitle = "".toCaString())

        assertInHeader(Workspace.Type.EXPLORER.label.get(context))
        composeTestRule.onNodeWithTag(TEST_TAG_WORKSPACE_CARD_INFOBAR, useUnmergedTree = true).assertDoesNotExist()
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
