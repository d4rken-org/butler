package eu.darken.butler.explorer.ui.explorer.util

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.Storage
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.explorer.core.ExplorerNavigation
import eu.darken.butler.explorer.core.engine.ExplorerItem
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

    /** Only the location, so the sheet reads the live row instead of the one it was opened from. */
    @Test
    fun `a network location maps to its id`() {
        val item = MockDataProvider.createMockStorageNetwork()

        val result = calculator.calculateInfo(listOf(item), listOf(item))

        result.shouldBeInstanceOf<ExplorerDialogState.ItemInfo.InfoContext.SingleNetwork>()
        result.locationId shouldBe item.location.id
        result.item shouldBe null
    }

    @Test
    fun `local storage capacity is taken from the live items`() {
        val stale = createLocalStorage(totalBytes = null, availableBytes = null)
        val fresh = createLocalStorage(totalBytes = 128 * 1024L, availableBytes = 64 * 1024L)

        val result = calculator.calculateInfo(listOf(stale), listOf(fresh))

        result.shouldBeInstanceOf<ExplorerDialogState.ItemInfo.InfoContext.SingleLocalStorage>()
        result.item.totalBytes shouldBe 128 * 1024L
        result.item.availableBytes shouldBe 64 * 1024L
    }

    @Test
    fun `empty selection has no info`() {
        calculator.calculateInfo(emptyList(), listOf(MockDataProvider.createMockDirectory())) shouldBe null
    }

    private fun createLocalStorage(
        totalBytes: Long?,
        availableBytes: Long?,
    ) = ExplorerItem.Storage.Local(
        localId = "internal-public",
        displayName = "Internal Storage".toCaString(),
        displayIcon = Icons.TwoTone.Storage,
        target = ExplorerNavigation.Target.Directory(LocalPath.build("/storage/emulated/0")),
        totalBytes = totalBytes,
        availableBytes = availableBytes,
    )
}
