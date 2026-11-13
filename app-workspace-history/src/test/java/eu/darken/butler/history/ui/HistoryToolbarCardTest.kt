package eu.darken.butler.history.ui

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.history.R
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.operations.history.HistoryFilter
import eu.darken.butler.workspace.core.operations.history.HistoryOutcome
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

    private val context: Context = ApplicationProvider.getApplicationContext()

    private val cardTag = "card"

    private val unfilteredSummary: String
        get() = context.resources.getQuantityString(R.plurals.history_summary_total, 200, 200)
    private val filteredSummary: String
        get() = context.resources.getQuantityString(R.plurals.history_summary_filtered, 12, 12, 200)
    private val clearFilterLabel: String
        get() = context.getString(R.string.history_toolbar_reset_filter_content_description)

    private var expandCalls = 0
    private var clearFilterCalls = 0

    private fun setCard(
        collapsedFraction: Float = 1f,
        filtered: Boolean = false,
        withExpand: Boolean = true,
        cardModifier: Modifier = Modifier,
    ) {
        composeTestRule.setContent {
            PreviewWrapper {
                Box(modifier = cardModifier) {
                    HistoryToolbarCard(
                        modifier = Modifier.testTag(cardTag),
                        workspaceId = Workspace.Id(),
                        // Split-pane layout: keeps the mascot-bearing workspace button, which
                        // Robolectric cannot rasterise, out of the toolbar cutout.
                        design = WorkspaceDesign(layout = WorkspaceDesign.Layout.DUAL_VERTICAL),
                        filter = if (filtered) {
                            HistoryFilter(outcomes = setOf(HistoryOutcome.FAILED))
                        } else {
                            HistoryFilter()
                        },
                        entryCount = if (filtered) 12 else 200,
                        totalCount = 200,
                        collapsedFraction = collapsedFraction,
                        onExpand = if (withExpand) {
                            { expandCalls++ }
                        } else {
                            null
                        },
                        onRemoveOutcome = {},
                        onRemoveKind = {},
                        onRemovePathScope = {},
                        onAddFilter = {},
                        onClearFilter = { clearFilterCalls++ },
                    )
                }
            }
        }
        composeTestRule.waitForIdle()
    }

    private fun tapCentreOfText(text: String) {
        composeTestRule.onNodeWithText(text, useUnmergedTree = true).performTouchInput { click() }
        composeTestRule.waitForIdle()
    }

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

    @Test
    fun `the tappable collapsed toolbar keeps the collapsed toolbar height`() {
        setCard()

        val card = composeTestRule.onNodeWithTag(cardTag).getUnclippedBoundsInRoot()

        (card.bottom - card.top) shouldBe WorkspaceToolbarDefaults.MinHeightCollapsed
    }

    @Test
    fun `tapping the collapsed summary asks the bar to expand`() {
        setCard()

        tapCentreOfText(unfilteredSummary)

        expandCalls shouldBe 1
    }

    @Test
    fun `tapping the collapsed history icon asks the bar to expand`() {
        setCard()

        composeTestRule.onNode(hasClickAction() and hasText(unfilteredSummary)).performTouchInput {
            // The icon is the first child of the target and is 20dp wide.
            click(Offset(10.dp.toPx(), centerY))
        }
        composeTestRule.waitForIdle()

        expandCalls shouldBe 1
    }

    @Test
    fun `tapping the expanded title does not ask the bar to expand`() {
        setCard(collapsedFraction = 0f)

        tapCentreOfText(context.getString(R.string.history_workspace_title))

        expandCalls shouldBe 0
    }

    @Test
    fun `the collapsed summary is not tappable without an expand handler`() {
        setCard(withExpand = false)

        composeTestRule.onNodeWithText(unfilteredSummary).assertIsDisplayed()
        composeTestRule.onAllNodes(hasClickAction() and hasText(unfilteredSummary)).assertCountEquals(0)
    }

    private fun assertSummaryAndClearFilterStaySeparate() {
        tapCentreOfText(filteredSummary)
        expandCalls shouldBe 1

        composeTestRule.onNodeWithContentDescription(clearFilterLabel).performTouchInput { click() }
        composeTestRule.waitForIdle()
        clearFilterCalls shouldBe 1
        expandCalls shouldBe 1
    }

    @Test
    fun `the collapsed filtered summary expands while clear-filter keeps its own action`() {
        setCard(filtered = true)

        assertSummaryAndClearFilterStaySeparate()
    }

    @Test
    fun `the clear-filter edge facing the summary keeps its own action`() {
        setCard(filtered = true)

        composeTestRule.onNodeWithContentDescription(clearFilterLabel).performTouchInput {
            click(Offset(1f, centerY))
        }
        composeTestRule.waitForIdle()

        clearFilterCalls shouldBe 1
        expandCalls shouldBe 0
    }

    @Test
    fun `a narrow collapsed toolbar keeps the summary and clear-filter separate`() {
        setCard(filtered = true, cardModifier = Modifier.width(280.dp))

        assertSummaryAndClearFilterStaySeparate()
    }
}
