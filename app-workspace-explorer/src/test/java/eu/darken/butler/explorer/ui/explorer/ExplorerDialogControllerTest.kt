package eu.darken.butler.explorer.ui.explorer

import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.explorer.core.FileTypeFilter
import eu.darken.butler.explorer.core.FilterState
import eu.darken.butler.explorer.ui.explorer.dialogs.ExplorerDialogEvent
import eu.darken.butler.explorer.ui.explorer.dialogs.ExplorerDialogState
import eu.darken.butler.explorer.ui.explorer.dialogs.RevealedPassword
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test
import testhelpers.BaseTest
import java.io.File
import kotlin.uuid.Uuid

class ExplorerDialogControllerTest : BaseTest() {

    private fun controller(
        filterState: FilterState = FilterState(),
        useRegex: Boolean = false,
        clearSelection: () -> Unit = {},
    ) = ExplorerDialogController(
        filterState = { filterState },
        useRegexPatterns = { useRegex },
        clearSelection = clearSelection,
        tag = "test",
    )

    private fun path(name: String) = LocalPath.build(File("/tmp/dialog-test", name))

    @Test
    fun `dialog slot is last-write-wins and dismissable`() {
        val controller = controller()

        controller.state.value shouldBe ExplorerDialogState.None

        controller.show(ExplorerDialogState.CreateItem)
        controller.current() shouldBe ExplorerDialogState.CreateItem

        controller.show(ExplorerDialogState.EmptyTrashConfirmation)
        controller.current() shouldBe ExplorerDialogState.EmptyTrashConfirmation

        controller.dismiss()
        controller.current() shouldBe ExplorerDialogState.None
    }

    @Test
    fun `rename event shows dialog and clears selection`() {
        var selectionCleared = false
        val controller = controller(clearSelection = { selectionCleared = true })
        val target = path("rename-me.txt")

        controller.handle(ExplorerDialogEvent.ShowRename(target))

        controller.current() shouldBe ExplorerDialogState.Rename(target)
        selectionCleared shouldBe true
    }

    @Test
    fun `delete confirmation event carries items and perm-delete flag`() {
        val controller = controller()
        val items = setOf(path("a"), path("b"))

        controller.handle(ExplorerDialogEvent.ShowDeleteConfirmation(items, initialPermanentDelete = true))

        controller.current() shouldBe ExplorerDialogState.DeleteConfirmation(items, initialPermanentDelete = true)
    }

    @Test
    fun `filter options event snapshots current filter state`() {
        val controller = controller(
            filterState = FilterState(
                includePattern = "*.txt",
                excludePattern = "tmp*",
                fileTypeFilter = FileTypeFilter.FILES_ONLY,
            ),
            useRegex = true,
        )

        controller.handle(ExplorerDialogEvent.ShowFilterOptions)

        controller.current() shouldBe ExplorerDialogState.FilterOptions(
            includePattern = "*.txt",
            excludePattern = "tmp*",
            fileTypeFilter = FileTypeFilter.FILES_ONLY,
            useRegexPatterns = true,
        )
    }

    @Test
    fun `dismissIfCurrent claims the expected dialog exactly once`() {
        val controller = controller()
        val shown = ExplorerDialogState.DeleteConfirmation(setOf(path("a")))
        controller.show(shown)

        controller.dismissIfCurrent(ExplorerDialogState.DeleteConfirmation(setOf(path("a")))) shouldBe true
        controller.current() shouldBe ExplorerDialogState.None

        // A second caller finds the slot already claimed.
        controller.dismissIfCurrent(shown) shouldBe false
    }

    @Test
    fun `dismissIfCurrent leaves a different dialog alone`() {
        val controller = controller()
        controller.show(ExplorerDialogState.CreateItem)

        controller.dismissIfCurrent(ExplorerDialogState.DeleteConfirmation(setOf(path("a")))) shouldBe false

        controller.current() shouldBe ExplorerDialogState.CreateItem
    }

    @Test
    fun `reopening the network info sheet for one location yields a new sheet`() {
        val locationId = Uuid.random()

        val first = ExplorerDialogState.ItemInfo.InfoContext.SingleNetwork(locationId)
        val second = ExplorerDialogState.ItemInfo.InfoContext.SingleNetwork(locationId)

        second.sheetInstanceId shouldNotBe first.sheetInstanceId
    }

    @Test
    fun `what an open network info sheet loads keeps its identity`() {
        val controller = controller()
        val opened = ExplorerDialogState.ItemInfo.InfoContext.SingleNetwork(Uuid.random())
        controller.show(ExplorerDialogState.ItemInfo(opened))

        controller.updateSingleNetwork(opened.locationId, opened.sheetInstanceId) { it.copy(isRevealing = true) }

        val current = (controller.current() as ExplorerDialogState.ItemInfo).context
            as ExplorerDialogState.ItemInfo.InfoContext.SingleNetwork
        current.isRevealing shouldBe true
        current.sheetInstanceId shouldBe opened.sheetInstanceId
    }

    @Test
    fun `a result for a sheet that is gone is dropped`() {
        val controller = controller()
        val opened = ExplorerDialogState.ItemInfo.InfoContext.SingleNetwork(Uuid.random())
        controller.show(ExplorerDialogState.ItemInfo(opened))
        controller.dismiss()

        controller.updateSingleNetwork(opened.locationId, opened.sheetInstanceId) { it.copy(isRevealing = true) }

        controller.current() shouldBe ExplorerDialogState.None
    }

    @Test
    fun `a result for a dismissed sheet does not land on its reopening`() {
        val controller = controller()
        val locationId = Uuid.random()

        val first = ExplorerDialogState.ItemInfo.InfoContext.SingleNetwork(locationId)
        controller.show(ExplorerDialogState.ItemInfo(first))
        controller.dismiss()

        val second = ExplorerDialogState.ItemInfo.InfoContext.SingleNetwork(locationId)
        controller.show(ExplorerDialogState.ItemInfo(second))

        // The reveal the user started on the first sheet, arriving after they opened the second one.
        controller.updateSingleNetwork(locationId, first.sheetInstanceId) {
            it.copy(revealed = RevealedPassword("hunter2"), isRevealing = false)
        }

        val current = (controller.current() as ExplorerDialogState.ItemInfo).context
            as ExplorerDialogState.ItemInfo.InfoContext.SingleNetwork
        current.sheetInstanceId shouldBe second.sheetInstanceId
        current.revealed shouldBe null
    }

    @Test
    fun `create and dismiss events update the slot`() {
        val controller = controller()

        controller.handle(ExplorerDialogEvent.ShowCreateItem)
        controller.current() shouldBe ExplorerDialogState.CreateItem

        controller.handle(ExplorerDialogEvent.Dismiss)
        controller.current() shouldBe ExplorerDialogState.None
    }
}
