package eu.darken.butler.explorer.ui.explorer.actions

import eu.darken.butler.explorer.core.ExplorerViewStyle
import eu.darken.butler.explorer.core.engine.ExplorerItem
import eu.darken.butler.explorer.core.engine.ExplorerLocation
import eu.darken.butler.explorer.core.favorites.ExplorerFavoritesRepo
import eu.darken.butler.explorer.ui.explorer.preview.MockDataProvider
import eu.darken.butler.explorer.ui.explorer.util.ExplorerSelectionState
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import testhelpers.BaseTest

class DeviceActionProviderTest : BaseTest() {

    private val favoritesRepo = mockk<ExplorerFavoritesRepo>().apply {
        every { isFavorite(any()) } returns false
    }

    private val storage = MockDataProvider.createMockStorageSAF()

    private fun actionsFor(vararg selected: ExplorerItem) = DeviceActionProvider(favoritesRepo).getActions(
        location = ExplorerLocation.Device(items = listOf(storage)),
        selectionState = ExplorerSelectionState(selectedItems = selected.toSet()),
        viewStyle = ExplorerViewStyle.List(),
        trashEnabled = false,
    )

    /** Refreshing is blocked while items are selected, so it must not be offered there either. */
    @Test
    fun `refreshing is only offered without a selection`() {
        actionsFor().any { it is ExplorerActionBarItem.Common.Refresh } shouldBe true
        actionsFor(storage).any { it is ExplorerActionBarItem.Common.Refresh } shouldBe false
    }

    @Test
    fun `without a selection the list can be sorted, filtered and restyled`() {
        val actions = actionsFor()

        actions.any { it is ExplorerActionBarItem.Common.Sort } shouldBe true
        actions.any { it is ExplorerActionBarItem.Common.Filter } shouldBe true
        actions.any { it is ExplorerActionBarItem.Common.UpdateViewStyle } shouldBe true
    }

    @Test
    fun `a selection hides sort, filter and view-style`() {
        val actions = actionsFor(storage)

        actions.any { it is ExplorerActionBarItem.Common.Sort } shouldBe false
        actions.any { it is ExplorerActionBarItem.Common.Filter } shouldBe false
        actions.any { it is ExplorerActionBarItem.Common.UpdateViewStyle } shouldBe false
    }
}
