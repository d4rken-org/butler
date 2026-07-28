package eu.darken.butler.explorer.ui.explorer.util

import eu.darken.butler.explorer.ui.explorer.dialogs.ExplorerDialogState
import eu.darken.butler.explorer.ui.explorer.preview.MockDataProvider
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import testhelpers.BaseTest

class ItemInfoCalculatorTest : BaseTest() {

    private val calculator = ItemInfoCalculator()

    @Test
    fun `stale selection is resolved against the live items`() {
        val stale = MockDataProvider.createMockDirectory("Documents", childCount = null)
        val fresh = MockDataProvider.createMockDirectory("Documents", childCount = 5)

        val result = calculator.calculateInfo(listOf(stale), listOf(fresh))

        result.shouldBeInstanceOf<ExplorerDialogState.ItemInfo.InfoContext.SingleDirectory>()
        result.item.childCount shouldBe 5
    }

    @Test
    fun `stale selection is kept when it is gone from the live items`() {
        val stale = MockDataProvider.createMockDirectory("Documents", childCount = null)
        val other = MockDataProvider.createMockDirectory("Downloads", childCount = 5)

        val result = calculator.calculateInfo(listOf(stale), listOf(other))

        result.shouldBeInstanceOf<ExplorerDialogState.ItemInfo.InfoContext.SingleDirectory>()
        result.item shouldBe stale
    }

    @Test
    fun `local storage maps to its own info context`() {
        val item = MockDataProvider.createMockStorageLocal()

        val result = calculator.calculateInfo(listOf(item), listOf(item))

        result.shouldBeInstanceOf<ExplorerDialogState.ItemInfo.InfoContext.SingleLocalStorage>()
        result.item shouldBe item
    }

    @Test
    fun `empty selection has no info`() {
        calculator.calculateInfo(emptyList(), listOf(MockDataProvider.createMockDirectory())) shouldBe null
    }
}
