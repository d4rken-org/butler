package eu.darken.butler.explorer.ui.explorer.actions

import eu.darken.butler.explorer.core.ExplorerViewStyle
import eu.darken.butler.explorer.core.engine.ExplorerItem
import eu.darken.butler.explorer.core.engine.ExplorerLocation
import eu.darken.butler.explorer.ui.explorer.preview.MockDataProvider
import eu.darken.butler.explorer.ui.explorer.util.ExplorerSelectionState
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import testhelpers.BaseTest
import kotlin.uuid.Uuid

class NetworkActionProviderTest : BaseTest() {

    private val first = MockDataProvider.createMockStorageNetwork(name = "Home NAS")
    private val second = MockDataProvider.createMockStorageNetwork(
        name = "Work NAS",
        id = Uuid.parse("22222222-2222-2222-2222-222222222222"),
    )

    private fun actionsFor(vararg selected: ExplorerItem) = NetworkActionProvider().getActions(
        location = ExplorerLocation.Network(items = listOf(first, second)),
        selectionState = ExplorerSelectionState(selectedItems = selected.toSet()),
        viewStyle = ExplorerViewStyle.List(),
        trashEnabled = false,
    )

    @Test
    fun `without a selection only adding is offered`() {
        val actions = actionsFor()

        actions.any { it is ExplorerActionBarItem.Network.AddLocation } shouldBe true
        actions.any { it is ExplorerActionBarItem.Network.EditLocation } shouldBe false
        actions.any { it is ExplorerActionBarItem.Network.RemoveLocation } shouldBe false
    }

    @Test
    fun `one selected location offers edit, rename and remove`() {
        val actions = actionsFor(first)

        actions.any { it is ExplorerActionBarItem.Network.EditLocation } shouldBe true
        actions.any { it is ExplorerActionBarItem.Network.RenameLocation } shouldBe true
        actions.any { it is ExplorerActionBarItem.Network.RemoveLocation } shouldBe true
        actions.any { it is ExplorerActionBarItem.Network.AddLocation } shouldBe false
    }

    @Test
    fun `several selected locations only offer removal`() {
        val actions = actionsFor(first, second)

        actions.any { it is ExplorerActionBarItem.Network.RemoveLocation } shouldBe true
        actions.any { it is ExplorerActionBarItem.Network.EditLocation } shouldBe false
        actions.any { it is ExplorerActionBarItem.Network.RenameLocation } shouldBe false
    }

    /**
     * Properties are empty for a location and a new tab would skip the sign-in check, so neither is
     * offered here.
     */
    @Test
    fun `a selected location offers neither properties nor opening in a new tab`() {
        val actions = actionsFor(first)

        actions.any { it is ExplorerActionBarItem.Common.Info } shouldBe false
        actions.any { it is ExplorerActionBarItem.Directory.OpenInNewTabs } shouldBe false
    }

    @Test
    fun `browsing actions are always offered`() {
        listOf(actionsFor(), actionsFor(first)).forEach { actions ->
            actions.any { it is ExplorerActionBarItem.Common.Refresh } shouldBe true
            actions.any { it is ExplorerActionBarItem.Common.Sort } shouldBe true
            actions.any { it is ExplorerActionBarItem.Common.Filter } shouldBe true
            actions.any { it is ExplorerActionBarItem.Common.UpdateViewStyle } shouldBe true
        }
    }
}
