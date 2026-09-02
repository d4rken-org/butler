package eu.darken.butler.common.compose.dragselect

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.TouchInjectionScope
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.compose.PreviewWrapper
import io.kotest.matchers.shouldBe
import org.junit.Test
import testhelpers.ComposeTest

/**
 * A 3-column grid of 40dp cells in a 480dp viewport: index 0..2 sit in the top auto-scroll edge
 * zone ([EdgeAutoScroller.DefaultEdge], 56dp), every index these tests touch is below it.
 */
class DragSelectGridTest : ComposeTest() {

    private val keys = (0..11).map { "item$it" }

    @Test
    fun `a long press without movement selects the anchor`() {
        val harness = GridHarness(keys)
        composeTestRule.setHarness(harness)

        composeTestRule.longPressDrag(harness, 4)

        harness.selection.value shouldBe setOf("item4")
    }

    @Test
    fun `a drag across a row boundary selects the whole index range`() {
        val harness = GridHarness(keys)
        composeTestRule.setHarness(harness)

        composeTestRule.longPressDrag(harness, 4, 5, 6, 7)

        harness.selection.value shouldBe setOf("item4", "item5", "item6", "item7")
    }

    @Test
    fun `retreating across a row boundary drops the items left behind`() {
        val harness = GridHarness(keys)
        composeTestRule.setHarness(harness)

        composeTestRule.longPressDrag(harness, 3, 6, 8, 4)

        harness.selection.value shouldBe setOf("item3", "item4")
    }

    @Test
    fun `a pre-existing selection survives the drag`() {
        val harness = GridHarness(keys, initialSelection = setOf("item11"))
        composeTestRule.setHarness(harness)

        composeTestRule.longPressDrag(harness, 4, 5, 4)

        harness.selection.value shouldBe setOf("item4", "item11")
    }

    @Test
    fun `a declined anchor changes nothing`() {
        val harness = GridHarness(keys, enabled = { false })
        composeTestRule.setHarness(harness)

        composeTestRule.longPressDrag(harness, 4, 5, 6)

        harness.selection.value shouldBe emptySet()
        harness.longClicks shouldBe listOf("item4")
    }

    @Test
    fun `content padding is accounted for when resolving the pressed item`() {
        val harness = GridHarness(keys, contentPadding = PaddingValues(top = 60.dp))
        composeTestRule.setHarness(harness)

        composeTestRule.longPressDrag(harness, 4, 7)

        harness.selection.value shouldBe setOf("item4", "item5", "item6", "item7")
    }

    @Test
    fun `horizontal content padding is accounted for in the trailing column`() {
        val harness = GridHarness(keys, contentPadding = PaddingValues(start = 48.dp, top = 60.dp))
        composeTestRule.setHarness(harness)

        // item5 sits in the last column; without undoing the leading inset the press misses it.
        composeTestRule.longPressDrag(harness, 5)

        harness.selection.value shouldBe setOf("item5")
    }

    @Test
    fun `a gap between cells keeps the last endpoint`() {
        // The horizontal spacing between two cells belongs to no item at all.
        val harness = GridHarness(keys)
        composeTestRule.setHarness(harness)

        composeTestRule.onNodeWithTag(GRID_TAG).performTouchInput {
            down(harness.itemCenter(this, 4))
            advanceEventTime(LONG_PRESS_MS)
            moveTo(harness.itemCenter(this, 5))
            advanceEventTime(16)
            moveTo(harness.gapAfter(this, 5))
            advanceEventTime(16)
            up()
        }
        composeTestRule.waitForIdle()

        harness.selection.value shouldBe setOf("item4", "item5")
    }
}

private const val GRID_TAG = "dragselect-grid"
private const val LONG_PRESS_MS = 1000L
private const val COLUMNS = 3
private val CELL_HEIGHT = 40.dp
private val CELL_SPACING = 8.dp
private val VIEWPORT_HEIGHT = 480.dp

private class GridHarness(
    val allKeys: List<String>,
    selectableKeys: List<String> = allKeys,
    initialSelection: Set<String> = emptySet(),
    val enabled: (String) -> Boolean = { true },
    val contentPadding: PaddingValues = PaddingValues(0.dp),
) {
    val selectableKeys: MutableState<List<String>> = mutableStateOf(selectableKeys)
    val selection: MutableState<Set<String>> = mutableStateOf(initialSelection)
    val longClicks = mutableListOf<String>()
    lateinit var gridState: LazyGridState

    fun itemCenter(scope: TouchInjectionScope, index: Int): Offset = with(scope) {
        val rowHeight = CELL_HEIGHT.toPx() + CELL_SPACING.toPx()
        val cellLeft = (index % COLUMNS) * (slotWidth(scope) + CELL_SPACING.toPx())
        Offset(
            x = startPaddingPx(scope) + cellLeft + slotWidth(scope) / 2,
            y = contentPadding.calculateTopPadding().toPx() + (index / COLUMNS) * rowHeight + CELL_HEIGHT.toPx() / 2,
        )
    }

    /** The spacing strip to the right of [index], which is part of no cell. */
    fun gapAfter(scope: TouchInjectionScope, index: Int): Offset = with(scope) {
        val cellLeft = (index % COLUMNS) * (slotWidth(scope) + CELL_SPACING.toPx())
        itemCenter(scope, index).copy(
            x = startPaddingPx(scope) + cellLeft + slotWidth(scope) + CELL_SPACING.toPx() / 2,
        )
    }

    /** Width of one column slot, matching GridCells.Fixed inside the padded content area. */
    private fun slotWidth(scope: TouchInjectionScope): Float = with(scope) {
        val content = width.toFloat() - startPaddingPx(scope) - endPaddingPx(scope)
        (content - CELL_SPACING.toPx() * (COLUMNS - 1)) / COLUMNS
    }

    private fun startPaddingPx(scope: TouchInjectionScope): Float = with(scope) {
        contentPadding.calculateStartPadding(LayoutDirection.Ltr).toPx()
    }

    private fun endPaddingPx(scope: TouchInjectionScope): Float = with(scope) {
        contentPadding.calculateEndPadding(LayoutDirection.Ltr).toPx()
    }
}

@Composable
private fun GridHarnessContent(harness: GridHarness) {
    val state = rememberLazyGridState()
    harness.gridState = state
    val selectable = harness.selectableKeys
    LazyVerticalGrid(
        columns = GridCells.Fixed(COLUMNS),
        state = state,
        modifier = Modifier
            .fillMaxWidth()
            .height(VIEWPORT_HEIGHT)
            .testTag(GRID_TAG)
            .gridDragSelect(
                state = state,
                orderedKeys = { selectable.value },
                currentSelection = { harness.selection.value },
                onSelectionChange = { harness.selection.value = it },
                enabled = harness.enabled,
                contentPadding = harness.contentPadding,
            ),
        contentPadding = harness.contentPadding,
        verticalArrangement = Arrangement.spacedBy(CELL_SPACING),
        horizontalArrangement = Arrangement.spacedBy(CELL_SPACING),
    ) {
        items(harness.allKeys, key = { it }) { key ->
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(CELL_HEIGHT)
                    .combinedClickable(
                        onClick = {},
                        onLongClick = { harness.longClicks += key },
                    ),
            )
        }
    }
}

private fun ComposeContentTestRule.setHarness(harness: GridHarness) {
    setContent {
        PreviewWrapper {
            GridHarnessContent(harness)
        }
    }
    waitForIdle()
}

private fun ComposeContentTestRule.longPressDrag(harness: GridHarness, vararg indices: Int) {
    onNodeWithTag(GRID_TAG).performTouchInput {
        down(harness.itemCenter(this, indices.first()))
        advanceEventTime(LONG_PRESS_MS)
        indices.drop(1).forEach { index ->
            moveTo(harness.itemCenter(this, index))
            advanceEventTime(16)
        }
        up()
    }
    waitForIdle()
}
