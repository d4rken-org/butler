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

    @Test
    fun `refreshing is offered either way`() {
        listOf(actionsFor(), actionsFor(storage)).forEach { actions ->
            actions.any { it is ExplorerActionBarItem.Common.Refresh } shouldBe true
        }
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
