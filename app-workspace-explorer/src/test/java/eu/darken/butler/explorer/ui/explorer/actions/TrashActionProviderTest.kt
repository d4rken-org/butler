package eu.darken.butler.explorer.ui.explorer.actions

import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.explorer.core.ExplorerViewStyle
import eu.darken.butler.explorer.core.engine.ExplorerItem
import eu.darken.butler.explorer.core.engine.ExplorerLocation
import eu.darken.butler.explorer.core.engine.TrashItemReference
import eu.darken.butler.explorer.ui.explorer.preview.MockDataProvider
import eu.darken.butler.explorer.ui.explorer.util.ExplorerSelectionState
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import testhelpers.BaseTest

class TrashActionProviderTest : BaseTest() {

    private fun provider() = TrashActionProvider()

    private fun root(items: List<ExplorerItem>?) = ExplorerLocation.Trash.Root(
        items = items,
        info = ExplorerLocation.Trash.Root.Info(itemCount = items?.size ?: 0, totalSize = 0L),
    )

    private fun nested(items: List<ExplorerItem>?) = ExplorerLocation.Trash.Nested(
        items = items,
        parentItem = TrashItemReference.from(MockDataProvider.createMockTrashItem()),
        currentPath = LocalPath.build("/trash/dir"),
        relativePath = "dir",
    )

    private fun actionsFor(location: ExplorerLocation) = provider().getActions(
        location = location,
        selectionState = ExplorerSelectionState(),
        viewStyle = ExplorerViewStyle.List(),
        trashEnabled = true,
    )

    private fun List<ExplorerActionBarItem>.hasSecondaryBrowsingActions(): Boolean =
        any { it is ExplorerActionBarItem.Common.Sort } &&
            any { it is ExplorerActionBarItem.Common.Filter } &&
            any { it is ExplorerActionBarItem.Common.UpdateViewStyle }

    @Test
    fun `empty root trash hides sort, filter and view-style actions`() {
        val actions = actionsFor(root(emptyList()))

        actions.any { it is ExplorerActionBarItem.Common.Sort } shouldBe false
        actions.any { it is ExplorerActionBarItem.Common.Filter } shouldBe false
        actions.any { it is ExplorerActionBarItem.Common.UpdateViewStyle } shouldBe false
        // Refresh and EmptyBin stay (EmptyBin disabled when empty).
        actions.any { it is ExplorerActionBarItem.Common.Refresh } shouldBe true
        actions.any { it is ExplorerActionBarItem.Trash.EmptyBin } shouldBe true
    }

    @Test
    fun `non-empty root trash shows sort, filter and view-style actions`() {
        val actions = actionsFor(root(listOf(MockDataProvider.createMockTrashItem())))

        actions.hasSecondaryBrowsingActions() shouldBe true
    }

    @Test
    fun `loading root trash with null items shows the actions to avoid flicker`() {
        val actions = actionsFor(root(null))

        actions.hasSecondaryBrowsingActions() shouldBe true
    }

    @Test
    fun `empty nested trash hides sort, filter and view-style actions`() {
        val actions = actionsFor(nested(emptyList()))

        actions.any { it is ExplorerActionBarItem.Common.Sort } shouldBe false
        actions.any { it is ExplorerActionBarItem.Common.Filter } shouldBe false
        actions.any { it is ExplorerActionBarItem.Common.UpdateViewStyle } shouldBe false
        actions.any { it is ExplorerActionBarItem.Common.Refresh } shouldBe true
    }

    @Test
    fun `non-empty nested trash shows sort, filter and view-style actions`() {
        val actions = actionsFor(nested(listOf(MockDataProvider.createMockRegularFile())))

        actions.hasSecondaryBrowsingActions() shouldBe true
    }
}
