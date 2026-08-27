package eu.darken.butler.editor.ui.editor.text

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.Test
import testhelpers.ComposeTest

/**
 * Hit-testing a tap against a [LazyColumn] that carries top content padding.
 *
 * Geometry is pinned: items are 20px tall and the top padding is 64px, so a tap that ignores the
 * container/item coordinate difference lands three items away from the one under the finger.
 * Heights are explicit because Robolectric's text measurement is fake (see [ComposeTest]).
 */
class EditorPositionUtilsPaddingTest : ComposeTest() {

    private val itemCount = 12
    private val itemHeightPx = 20f
    private val visibleLineContent = (0 until itemCount).associate { it.toLong() to "line $it" }

    private var topPaddingPx by mutableFloatStateOf(64f)

    private lateinit var listState: LazyListState
    private lateinit var density: Density

    /** Physical item tops in the list's own coordinate space, published by the layout itself. */
    private val itemTops = mutableMapOf<Int, Float>()
    private var listTop = 0f

    private fun setList() {
        composeTestRule.setContent {
            density = LocalDensity.current
            listState = rememberLazyListState()
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(top = with(density) { topPaddingPx.toDp() }),
                modifier = Modifier
                    .fillMaxSize()
                    .onGloballyPositioned { listTop = it.positionInRoot().y },
            ) {
                items(itemCount) { index ->
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(with(density) { itemHeightPx.toDp() })
                            .onGloballyPositioned { itemTops[index] = it.positionInRoot().y },
                    )
                }
            }
        }
        composeTestRule.waitForIdle()
    }

    private fun physicalTopOf(index: Int): Float = itemTops.getValue(index) - listTop

    private fun tapAt(containerY: Float) = calculatePositionFromOffset(
        offset = Offset(0f, containerY),
        contentListState = listState,
        visibleLineContent = visibleLineContent,
        density = density,
        charWidthPx = 1f,
        tabSize = 4,
    )

    @Test
    fun `a tap on an item's physical centre resolves to that item`() {
        setList()

        for (index in listOf(0, 3, 7)) {
            tapAt(physicalTopOf(index) + itemHeightPx / 2f)!!.position.line shouldBe index.toLong()
        }
    }

    @Test
    fun `an item's physical top is its item offset converted to container space`() {
        setList()

        val layoutInfo = listState.layoutInfo
        for (item in layoutInfo.visibleItemsInfo) {
            physicalTopOf(item.index) shouldBe (item.offset - layoutInfo.viewportStartOffset).toFloat()
        }
    }

    @Test
    fun `taps follow the padding when it changes on an already laid out list`() {
        setList()
        tapAt(physicalTopOf(5) + itemHeightPx / 2f)!!.position.line shouldBe 5L

        topPaddingPx = 12f
        composeTestRule.waitForIdle()

        tapAt(physicalTopOf(5) + itemHeightPx / 2f)!!.position.line shouldBe 5L
    }

    @Test
    fun `a tap above the first item hits nothing`() {
        setList()

        tapAt(physicalTopOf(0) - 10f).shouldBeNull()
    }
}
