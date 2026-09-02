package eu.darken.butler.common.compose.dragselect

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.compose.PreviewWrapper
import io.kotest.matchers.shouldBe
import org.junit.Test
import testhelpers.ComposeTest

/**
 * Item geometry is chosen so every index these tests touch sits outside the auto-scroll edge zone
 * ([EdgeAutoScroller.DefaultEdge], 56dp): index 0 is inside it, indices 1..10 are not.
 */
class DragSelectListTest : ComposeTest() {

    private val keys = (0..9).map { "item$it" }

    /** Long enough to overflow the viewport, so scrolling is observable. */
    private val scrollableKeys = (0..19).map { "item$it" }

    @Test
    fun `a long press without movement selects the anchor`() {
        val harness = Harness(keys)
        composeTestRule.setHarness(harness)

        composeTestRule.longPressDrag(harness, 2)

        harness.selection.value shouldBe setOf("item2")
    }

    @Test
    fun `a long press and drag selects the whole range`() {
        val harness = Harness(keys)
        composeTestRule.setHarness(harness)

        composeTestRule.longPressDrag(harness, 1, 2, 3)

        harness.selection.value shouldBe setOf("item1", "item2", "item3")
    }

    @Test
    fun `retreating drops the items left behind but keeps the anchor`() {
        val harness = Harness(keys)
        composeTestRule.setHarness(harness)

        composeTestRule.longPressDrag(harness, 1, 2, 3, 4, 2)

        harness.selection.value shouldBe setOf("item1", "item2")
    }

    @Test
    fun `a pre-existing selection survives the drag and the retreat`() {
        val harness = Harness(keys, initialSelection = setOf("item8"))
        composeTestRule.setHarness(harness)

        composeTestRule.longPressDrag(harness, 1, 2, 3, 1)

        harness.selection.value shouldBe setOf("item1", "item8")
    }

    @Test
    fun `a selection hidden from the listing is never dropped`() {
        // Filtering keeps items selected that the list doesn't show - they must ride along.
        val harness = Harness(keys, initialSelection = setOf("hidden"))
        composeTestRule.setHarness(harness)

        composeTestRule.longPressDrag(harness, 1, 2, 1)

        harness.selection.value shouldBe setOf("item1", "hidden")
    }

    @Test
    fun `a non-selectable item between anchor and pointer is spanned, not selected`() {
        val harness = Harness(keys, selectableKeys = keys - "item2")
        composeTestRule.setHarness(harness)

        composeTestRule.longPressDrag(harness, 1, 2, 3)

        harness.selection.value shouldBe setOf("item1", "item3")
    }

    @Test
    fun `a declined anchor changes nothing`() {
        val harness = Harness(keys, enabled = { false })
        composeTestRule.setHarness(harness)

        composeTestRule.longPressDrag(harness, 1, 2, 3)

        harness.selection.value shouldBe emptySet()
        harness.selectionChanges shouldBe 0
    }

    @Test
    fun `content padding is accounted for when resolving the pressed item`() {
        val harness = Harness(keys, contentPadding = PaddingValues(top = 60.dp))
        composeTestRule.setHarness(harness)

        composeTestRule.longPressDrag(harness, 1, 2)

        harness.selection.value shouldBe setOf("item1", "item2")
    }

    @Test
    fun `a declined gesture still fires the item long click`() {
        val harness = Harness(keys, enabled = { false })
        composeTestRule.setHarness(harness)

        composeTestRule.longPressDrag(harness, 4, 3, 2)

        harness.longClicks shouldBe listOf("item4")
        harness.selection.value shouldBe emptySet()
    }

    @Test
    fun `a declined gesture leaves the drag to the list`() {
        // Items without a long click, or the tap detector's own post-long-press consumption would
        // swallow the movement before the list ever sees it.
        val harness = Harness(scrollableKeys, enabled = { false }, itemsAreClickable = false)
        composeTestRule.setHarness(harness)

        composeTestRule.longPressDrag(harness, 8, 6, 4, 2)

        harness.selection.value shouldBe emptySet()
        (harness.listState.firstVisibleItemIndex > 0) shouldBe true
    }

    @Test
    fun `a claimed gesture selects once and never fires the item click`() {
        val harness = Harness(keys)
        composeTestRule.setHarness(harness)

        composeTestRule.longPressDrag(harness, 2)

        harness.longClicks shouldBe listOf("item2")
        harness.clicks shouldBe emptyList()
        harness.selectionChanges shouldBe 1
        harness.selection.value shouldBe setOf("item2")
    }

    @Test
    fun `a claimed gesture does not scroll the list`() {
        val harness = Harness(scrollableKeys)
        composeTestRule.setHarness(harness)

        composeTestRule.longPressDrag(harness, 8, 6, 4, 2)

        harness.selection.value shouldBe setOf("item2", "item3", "item4", "item5", "item6", "item7", "item8")
        harness.listState.firstVisibleItemIndex shouldBe 0
        harness.listState.firstVisibleItemScrollOffset shouldBe 0
    }

    @Test
    fun `a tap still clicks the item`() {
        val harness = Harness(keys)
        composeTestRule.setHarness(harness)

        composeTestRule.onNodeWithTag(LIST_TAG).performTouchInput {
            down(Offset(centerX, harness.itemCenterY(this, 3)))
            up()
        }
        composeTestRule.waitForIdle()

        harness.clicks shouldBe listOf("item3")
        harness.selection.value shouldBe emptySet()
    }

    @Test
    fun `the range follows a listing that re-sorts mid-drag`() {
        val harness = Harness(keys)
        composeTestRule.setHarness(harness)

        // Events are only dispatched when the injection block returns, so a mid-drag data change
        // has to happen between two blocks of the same (still unreleased) gesture.
        composeTestRule.onNodeWithTag(LIST_TAG).performTouchInput {
            down(Offset(centerX, harness.itemCenterY(this, 1)))
            advanceEventTime(LONG_PRESS_MS)
            moveTo(Offset(centerX, harness.itemCenterY(this, 3)))
        }
        composeTestRule.waitForIdle()
        harness.selection.value shouldBe setOf("item1", "item2", "item3")

        // Same anchor and endpoint keys, different display order between them.
        harness.selectableKeys.value = listOf("item1", "item9", "item3") +
            (keys - setOf("item1", "item3", "item9"))
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag(LIST_TAG).performTouchInput {
            advanceEventTime(16)
            moveTo(Offset(centerX, harness.itemCenterY(this, 3) + 1f))
            up()
        }
        composeTestRule.waitForIdle()

        harness.selection.value shouldBe setOf("item1", "item9", "item3")
    }

    @Test
    fun `an anchor that vanishes mid-drag ends the session and keeps the selection`() {
        val harness = Harness(keys)
        composeTestRule.setHarness(harness)

        composeTestRule.onNodeWithTag(LIST_TAG).performTouchInput {
            down(Offset(centerX, harness.itemCenterY(this, 1)))
            advanceEventTime(LONG_PRESS_MS)
            moveTo(Offset(centerX, harness.itemCenterY(this, 2)))
        }
        composeTestRule.waitForIdle()
        harness.selection.value shouldBe setOf("item1", "item2")

        harness.selectableKeys.value = keys - "item1"
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag(LIST_TAG).performTouchInput {
            advanceEventTime(16)
            moveTo(Offset(centerX, harness.itemCenterY(this, 5)))
            up()
        }
        composeTestRule.waitForIdle()

        harness.selection.value shouldBe setOf("item1", "item2")
    }

    @Test
    fun `holding at the bottom edge scrolls and extends past the initial viewport`() {
        val harness = Harness(scrollableKeys)
        composeTestRule.mainClock.autoAdvance = false
        composeTestRule.setHarness(harness)

        composeTestRule.onNodeWithTag(LIST_TAG).performTouchInput {
            down(Offset(centerX, harness.itemCenterY(this, 1)))
            advanceEventTime(LONG_PRESS_MS)
            moveTo(Offset(centerX, height - 1f))
        }
        repeat(60) { composeTestRule.mainClock.advanceTimeByFrame() }

        // Everything from the anchor to the very last item, well past the initial viewport.
        harness.selection.value shouldBe (scrollableKeys - "item0").toSet()
        // The loop stops itself at the content bound instead of spinning.
        val settled = harness.selectionChanges
        repeat(20) { composeTestRule.mainClock.advanceTimeByFrame() }
        harness.selectionChanges shouldBe settled

        composeTestRule.onNodeWithTag(LIST_TAG).performTouchInput { up() }
        composeTestRule.mainClock.autoAdvance = true
    }

    @Test
    fun `an anchor that vanishes mid-scroll stops the auto scroll`() {
        // A finger resting at the edge sends no further pointer events, so only the frame loop can
        // notice that the session ended - otherwise it scrolls on to the content bound.
        val harness = Harness(scrollableKeys)
        composeTestRule.mainClock.autoAdvance = false
        composeTestRule.setHarness(harness)

        composeTestRule.onNodeWithTag(LIST_TAG).performTouchInput {
            down(Offset(centerX, harness.itemCenterY(this, 1)))
            advanceEventTime(LONG_PRESS_MS)
            moveTo(Offset(centerX, height - 1f))
        }
        repeat(5) { composeTestRule.mainClock.advanceTimeByFrame() }
        (harness.listState.firstVisibleItemIndex > 0) shouldBe true

        harness.selectableKeys.value = scrollableKeys - "item1"
        // One more frame for the in-flight scroll that started before the anchor vanished.
        composeTestRule.mainClock.advanceTimeByFrame()
        val stoppedAt = harness.listState.firstVisibleItemIndex
        val settled = harness.selectionChanges
        repeat(30) { composeTestRule.mainClock.advanceTimeByFrame() }

        harness.listState.firstVisibleItemIndex shouldBe stoppedAt
        harness.selectionChanges shouldBe settled

        composeTestRule.onNodeWithTag(LIST_TAG).performTouchInput { up() }
        composeTestRule.mainClock.autoAdvance = true
    }
}

private const val LIST_TAG = "dragselect-list"
private const val LONG_PRESS_MS = 1000L
private val ITEM_HEIGHT = 40.dp
private val VIEWPORT_HEIGHT = 480.dp

private class Harness(
    val allKeys: List<String>,
    selectableKeys: List<String> = allKeys,
    initialSelection: Set<String> = emptySet(),
    val enabled: (String) -> Boolean = { true },
    val contentPadding: PaddingValues = PaddingValues(0.dp),
    val itemsAreClickable: Boolean = true,
) {
    val selectableKeys: MutableState<List<String>> = mutableStateOf(selectableKeys)
    val selection: MutableState<Set<String>> = mutableStateOf(initialSelection)
    val clicks = mutableListOf<String>()
    val longClicks = mutableListOf<String>()
    var selectionChanges = 0
    lateinit var listState: LazyListState

    fun itemCenterY(scope: TouchInjectionScope, index: Int): Float = with(scope) {
        contentPadding.calculateTopPadding().toPx() + index * ITEM_HEIGHT.toPx() + ITEM_HEIGHT.toPx() / 2
    }
}

@Composable
private fun HarnessContent(harness: Harness) {
    val state = rememberLazyListState()
    harness.listState = state
    val selectable = harness.selectableKeys
    LazyColumn(
        state = state,
        modifier = Modifier
            .fillMaxWidth()
            .height(VIEWPORT_HEIGHT)
            .testTag(LIST_TAG)
            .listDragSelect(
                state = state,
                orderedKeys = { selectable.value },
                currentSelection = { harness.selection.value },
                onSelectionChange = {
                    harness.selectionChanges++
                    harness.selection.value = it
                },
                enabled = harness.enabled,
            ),
        contentPadding = harness.contentPadding,
    ) {
        items(harness.allKeys, key = { it }) { key ->
            val clickModifier = if (harness.itemsAreClickable) {
                Modifier.combinedClickable(
                    onClick = { harness.clicks += key },
                    onLongClick = { harness.longClicks += key },
                )
            } else {
                Modifier
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(ITEM_HEIGHT)
                    .then(clickModifier),
            )
        }
    }
}

private fun ComposeContentTestRule.setHarness(harness: Harness) {
    setContent {
        PreviewWrapper {
            HarnessContent(harness)
        }
    }
    waitForIdle()
}

/** Presses the first of [indices], holds past the long press timeout, then drags across the rest. */
private fun ComposeContentTestRule.longPressDrag(harness: Harness, vararg indices: Int) {
    onNodeWithTag(LIST_TAG).performTouchInput {
        down(Offset(centerX, harness.itemCenterY(this, indices.first())))
        advanceEventTime(LONG_PRESS_MS)
        indices.drop(1).forEach { index ->
            moveTo(Offset(centerX, harness.itemCenterY(this, index)))
            advanceEventTime(16)
        }
        up()
    }
    waitForIdle()
}
