package eu.darken.butler.explorer.ui.explorer.actions

import eu.darken.butler.common.files.LocalPath
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

class DirectoryActionProviderTest : BaseTest() {

    private val favoritesRepo = mockk<ExplorerFavoritesRepo>().apply {
        every { isFavorite(any()) } returns false
    }

    private fun provider() = DirectoryActionProvider(favoritesRepo)

    private fun directory(items: List<ExplorerItem.Path>?) = ExplorerLocation.Directory(
        items = items,
        info = ExplorerLocation.Directory.Info(isWritable = true),
        path = LocalPath.build("/home/user/dir"),
    )

    private fun actionsFor(items: List<ExplorerItem.Path>?) = provider().getActions(
        location = directory(items),
        selectionState = ExplorerSelectionState(),
        viewStyle = ExplorerViewStyle.List(),
        trashEnabled = false,
    )

    private fun List<ExplorerActionBarItem>.hasSecondaryBrowsingActions(): Boolean =
        any { it is ExplorerActionBarItem.Common.Sort } &&
            any { it is ExplorerActionBarItem.Common.Filter } &&
            any { it is ExplorerActionBarItem.Common.UpdateViewStyle }

    @Test
    fun `empty folder hides sort, filter and view-style actions`() {
        val actions = actionsFor(emptyList())

        actions.any { it is ExplorerActionBarItem.Common.Sort } shouldBe false
        actions.any { it is ExplorerActionBarItem.Common.Filter } shouldBe false
        actions.any { it is ExplorerActionBarItem.Common.UpdateViewStyle } shouldBe false
        // The always-relevant browsing actions stay.
        actions.any { it is ExplorerActionBarItem.Directory.Create } shouldBe true
        actions.any { it is ExplorerActionBarItem.Common.Refresh } shouldBe true
    }

    @Test
    fun `non-empty folder shows sort, filter and view-style actions`() {
        val actions = actionsFor(listOf(MockDataProvider.createMockRegularFile()))

        actions.hasSecondaryBrowsingActions() shouldBe true
    }

    @Test
    fun `filtered-to-empty still shows the actions since raw items are present`() {
        // The location carries the raw, unfiltered items; filtering happens downstream.
        // A folder filtered down to zero visible items still has raw items here.
        val actions = actionsFor(listOf(MockDataProvider.createMockRegularFile()))

        actions.hasSecondaryBrowsingActions() shouldBe true
    }

    @Test
    fun `loading folder with null items shows the actions to avoid flicker`() {
        val actions = actionsFor(null)

        actions.hasSecondaryBrowsingActions() shouldBe true
    }
}
