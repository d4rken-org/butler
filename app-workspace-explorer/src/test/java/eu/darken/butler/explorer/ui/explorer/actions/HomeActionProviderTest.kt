package eu.darken.butler.explorer.ui.explorer.actions

import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.explorer.core.ExplorerViewStyle
import eu.darken.butler.explorer.core.engine.ExplorerItem
import eu.darken.butler.explorer.core.engine.ExplorerLocation
import eu.darken.butler.explorer.ui.explorer.preview.MockDataProvider
import eu.darken.butler.explorer.ui.explorer.util.ExplorerSelectionState
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import testhelpers.BaseTest

class HomeActionProviderTest : BaseTest() {

    private val shortcut = MockDataProvider.createMockShortcut()

    private fun actionsFor(vararg selected: ExplorerItem) = HomeActionProvider().getActions(
        location = ExplorerLocation.Home(items = listOf(shortcut)),
        selectionState = ExplorerSelectionState(selectedItems = selected.toSet()),
        viewStyle = ExplorerViewStyle(),
        trashEnabled = false,
    )

    private fun actionsForFavorites(vararg selected: APath<*>) = HomeActionProvider().getActions(
        location = ExplorerLocation.Home(items = listOf(shortcut)),
        selectionState = ExplorerSelectionState(),
        viewStyle = ExplorerViewStyle(),
        trashEnabled = false,
        favoriteSelection = selected.toSet(),
    )

    private fun path(name: String) = LocalPath.build("/storage/emulated/0", name)

    /** Refreshing is blocked while items are selected, so it must not be offered there either. */
    @Test
    fun `refreshing is only offered without a selection`() {
        actionsFor().any { it is ExplorerActionBarItem.Common.Refresh } shouldBe true
        actionsFor(shortcut).any { it is ExplorerActionBarItem.Common.Refresh } shouldBe false
    }

    @Test
    fun `without a selection the list can be sorted, filtered and restyled`() {
        val actions = actionsFor()

        actions.any { it is ExplorerActionBarItem.Common.Sort } shouldBe true
        actions.any { it is ExplorerActionBarItem.Common.Filter } shouldBe true
        actions.any { it is ExplorerActionBarItem.Common.ViewOptions } shouldBe true
    }

    @Test
    fun `without a favorite selection the browse actions are the only ones offered`() {
        val actions = actionsForFavorites()

        actions.any { it is ExplorerActionBarItem.Common.RemoveFromFavorites } shouldBe false
        actions.any { it is ExplorerActionBarItem.Common.RenameFavorite } shouldBe false
        actions.size shouldBe 4
    }

    @Test
    fun `one selected favorite can be removed and named`() {
        val actions = actionsForFavorites(path("Download"))

        actions.filterIsInstance<ExplorerActionBarItem.Common.RemoveFromFavorites>()
            .single().items shouldContainExactly listOf(path("Download"))
        actions.filterIsInstance<ExplorerActionBarItem.Common.RenameFavorite>()
            .single().path shouldBe path("Download")
    }

    /** One name per favorite, so naming needs exactly one of them. */
    @Test
    fun `several selected favorites can only be removed`() {
        val actions = actionsForFavorites(path("Download"), path("DCIM"))

        actions.filterIsInstance<ExplorerActionBarItem.Common.RemoveFromFavorites>()
            .single().items shouldHaveSize 2
        actions.any { it is ExplorerActionBarItem.Common.RenameFavorite } shouldBe false
    }

    @Test
    fun `a favorite selection hides sort, filter, view-style and refresh`() {
        val actions = actionsForFavorites(path("Download"))

        actions.any { it is ExplorerActionBarItem.Common.Sort } shouldBe false
        actions.any { it is ExplorerActionBarItem.Common.Filter } shouldBe false
        actions.any { it is ExplorerActionBarItem.Common.ViewOptions } shouldBe false
        actions.any { it is ExplorerActionBarItem.Common.Refresh } shouldBe false
    }

    @Test
    fun `a selection hides sort, filter and view-style`() {
        val actions = actionsFor(shortcut)

        actions.any { it is ExplorerActionBarItem.Common.Sort } shouldBe false
        actions.any { it is ExplorerActionBarItem.Common.Filter } shouldBe false
        actions.any { it is ExplorerActionBarItem.Common.ViewOptions } shouldBe false
    }
}
