package eu.darken.butler.history.ui

import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.operations.history.HistoryFilter
import eu.darken.butler.workspace.ui.common.WorkspaceToolbarDefaults
import eu.darken.butler.workspace.ui.manager.WorkspaceDesign
import io.kotest.matchers.shouldBe
import org.junit.Test
import org.robolectric.annotation.GraphicsMode
import testhelpers.ComposeTest

// Real font metrics: the default graphics mode measures every line of text at 36dp (see
// ComposeTest), which is taller than the height floor this case is about.
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HistoryToolbarCardTest : ComposeTest() {

    private val cardTag = "card"

    @Test
    fun `the collapsed unfiltered toolbar is as tall as the other workspaces' toolbars`() {
        composeTestRule.setContent {
            PreviewWrapper {
                HistoryToolbarCard(
                    modifier = Modifier.testTag(cardTag),
                    workspaceId = Workspace.Id(),
                    // Split-pane layout: keeps the mascot-bearing workspace button, which
                    // Robolectric cannot rasterise, out of the toolbar cutout.
                    design = WorkspaceDesign(layout = WorkspaceDesign.Layout.DUAL_VERTICAL),
                    // Unfiltered: no clear-filter button to prop the row up to its own height.
                    filter = HistoryFilter(),
                    entryCount = 200,
                    totalCount = 200,
                    collapsedFraction = 1f,
                    onRemoveOutcome = {},
                    onRemoveKind = {},
                    onRemovePathScope = {},
                    onAddFilter = {},
                    onClearFilter = {},
                )
            }
        }

        val card = composeTestRule.onNodeWithTag(cardTag).getUnclippedBoundsInRoot()

        (card.bottom - card.top) shouldBe WorkspaceToolbarDefaults.MinHeightCollapsed
    }
}
