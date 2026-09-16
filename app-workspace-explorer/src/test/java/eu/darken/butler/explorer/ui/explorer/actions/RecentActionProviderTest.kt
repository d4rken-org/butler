package eu.darken.butler.explorer.ui.explorer.actions

import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.MimeInfo
import eu.darken.butler.common.files.local.LocalPathLookup
import eu.darken.butler.common.files.metadata.FileType
import eu.darken.butler.explorer.core.ExplorerViewStyle
import eu.darken.butler.explorer.core.engine.ExplorerItem
import eu.darken.butler.explorer.core.engine.ExplorerLocation
import eu.darken.butler.explorer.ui.explorer.util.ExplorerSelectionState
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import testhelpers.BaseTest

class RecentActionProviderTest : BaseTest() {

    private fun provider() = RecentActionProvider()

    private fun file(name: String, canWrite: Boolean? = null) = ExplorerItem.RegularFile(
        lookup = LocalPathLookup(
            lookedUp = LocalPath.build("/storage/emulated/0/Download/$name"),
            fileType = FileType.FILE,
            size = 1L,
            modifiedAt = null,
        ),
        mimeType = MimeInfo("application/pdf"),
        canWrite = canWrite,
    )

    private fun actionsFor(
        items: List<ExplorerItem> = listOf(file("a.pdf")),
        selected: Set<ExplorerItem> = emptySet(),
    ) = provider().getActions(
        location = ExplorerLocation.Recent(items = items, progress = null),
        selectionState = ExplorerSelectionState(
            selectedItems = selected,
            selectableItems = items.toSet(),
        ),
        viewStyle = ExplorerViewStyle(),
        trashEnabled = false,
    )

    @Test
    fun `browsing offers refresh, filter and the view options`() {
        val actions = actionsFor()

        actions.any { it is ExplorerActionBarItem.Common.Refresh } shouldBe true
        actions.any { it is ExplorerActionBarItem.Common.Filter } shouldBe true
        actions.any { it is ExplorerActionBarItem.Common.ViewOptions } shouldBe true
    }

    /** The loader's ranking by index time is the point of this listing, so it is not re-sortable. */
    @Test
    fun `browsing never offers a sort action`() {
        actionsFor().any { it is ExplorerActionBarItem.Common.Sort } shouldBe false
    }

    @Test
    fun `browsing offers neither create nor recursive sizes`() {
        val actions = actionsFor()

        actions.any { it is ExplorerActionBarItem.Directory.Create } shouldBe false
        actions.any { it is ExplorerActionBarItem.Directory.CalculateSizes } shouldBe false
    }

    @Test
    fun `an empty listing hides filter and the view options`() {
        val actions = actionsFor(items = emptyList())

        actions.any { it is ExplorerActionBarItem.Common.Refresh } shouldBe true
        actions.any { it is ExplorerActionBarItem.Common.Filter } shouldBe false
        actions.any { it is ExplorerActionBarItem.Common.ViewOptions } shouldBe false
    }

    @Test
    fun `a selection offers copy, cut, delete, share and info`() {
        val selected = file("a.pdf")
        val actions = actionsFor(items = listOf(selected, file("b.pdf")), selected = setOf(selected))

        actions.any { it is ExplorerActionBarItem.Directory.Copy } shouldBe true
        actions.any { it is ExplorerActionBarItem.Directory.Cut } shouldBe true
        actions.any { it is ExplorerActionBarItem.Directory.Delete } shouldBe true
        actions.any { it is ExplorerActionBarItem.Directory.Share } shouldBe true
        actions.any { it is ExplorerActionBarItem.Common.Info } shouldBe true
        actions.any { it is ExplorerActionBarItem.Directory.OpenInNewTabs } shouldBe true
        actions.any { it is ExplorerActionBarItem.Directory.SelectAll } shouldBe true
    }

    /** Both need a destination directory, which an index listing does not have. */
    @Test
    fun `a selection never offers compress or extract`() {
        val selected = file("archive.zip")
        val actions = actionsFor(items = listOf(selected), selected = setOf(selected))

        actions.any { it is ExplorerActionBarItem.Directory.Compress } shouldBe false
        actions.any { it is ExplorerActionBarItem.Directory.Extract } shouldBe false
    }

    @Test
    fun `a selection never offers create`() {
        val selected = file("a.pdf")

        actionsFor(items = listOf(selected), selected = setOf(selected))
            .any { it is ExplorerActionBarItem.Directory.Create } shouldBe false
    }

    @Test
    fun `renaming is offered for a single selected file only`() {
        val one = file("a.pdf")
        val two = file("b.pdf")

        actionsFor(items = listOf(one, two), selected = setOf(one))
            .any { it is ExplorerActionBarItem.Directory.Rename } shouldBe true
        actionsFor(items = listOf(one, two), selected = setOf(one, two))
            .any { it is ExplorerActionBarItem.Directory.Rename } shouldBe false
    }

    /** Recent has no writability of its own, so the selected rows answer for themselves. */
    @Test
    fun `cut and delete follow the selected items writability`() {
        val readOnly = file("locked.pdf", canWrite = false)
        val unknown = file("unknown.pdf", canWrite = null)

        val blocked = actionsFor(items = listOf(readOnly), selected = setOf(readOnly))
        blocked.single { it is ExplorerActionBarItem.Directory.Cut }.isEnabled shouldBe false
        blocked.single { it is ExplorerActionBarItem.Directory.Delete }.isEnabled shouldBe false

        val allowed = actionsFor(items = listOf(unknown), selected = setOf(unknown))
        allowed.single { it is ExplorerActionBarItem.Directory.Cut }.isEnabled shouldBe true
        allowed.single { it is ExplorerActionBarItem.Directory.Delete }.isEnabled shouldBe true
    }
}
