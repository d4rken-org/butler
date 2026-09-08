package eu.darken.butler.explorer.core.sorting

import android.content.Context
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.MimeInfo
import eu.darken.butler.common.files.local.LocalPathLookup
import eu.darken.butler.common.files.metadata.FileType
import eu.darken.butler.explorer.core.SortSettings
import eu.darken.butler.explorer.core.engine.ExplorerItem
import eu.darken.butler.explorer.core.sizes.DirectorySize
import eu.darken.butler.workspace.core.Workspace
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import org.junit.jupiter.api.Test
import testhelpers.BaseTest

class ExplorerItemSorterSizeTest : BaseTest() {

    private val sorter = ExplorerItemSorter(
        workspaceId = Workspace.Id(),
        context = mockk<Context>(relaxed = true),
    )

    private fun directory(name: String, computedSize: Long? = null) = ExplorerItem.RegularDirectory(
        lookup = LocalPathLookup(
            lookedUp = LocalPath.build("/a/$name"),
            fileType = FileType.DIRECTORY,
            size = null,
            modifiedAt = null,
        ),
        computedSize = computedSize?.let { DirectorySize(it, true) },
    )

    private fun file(name: String, size: Long) = ExplorerItem.RegularFile(
        lookup = LocalPathLookup(
            lookedUp = LocalPath.build("/a/$name"),
            fileType = FileType.FILE,
            size = size,
            modifiedAt = null,
        ),
        mimeType = MimeInfo("text/plain"),
    )

    private fun List<ExplorerItem>.names() = map { (it as ExplorerItem.Lookup).lookup.name }

    private val items = listOf(
        directory("big", computedSize = 117L * 1024 * 1024),
        directory("small", computedSize = 5L * 1024 * 1024),
        directory("zeta"),
        directory("alpha"),
        file("large.txt", 10L * 1024 * 1024),
        file("tiny.txt", 1024L),
    )

    @Test
    fun `size sort ranks folders and files in one list, unmeasured folders last`() {
        val sorted = sorter.sortItems(items, SortSettings(mode = SortSettings.Mode.SIZE))

        sorted.names() shouldBe listOf("tiny.txt", "small", "large.txt", "big", "alpha", "zeta")
    }

    @Test
    fun `reversing flips the ranking but leaves the unmeasured folders last and name-ordered`() {
        val sorted = sorter.sortItems(items, SortSettings(mode = SortSettings.Mode.SIZE, reversed = true))

        sorted.names() shouldBe listOf("big", "large.txt", "small", "tiny.txt", "alpha", "zeta")
    }

    @Test
    fun `name sort is unaffected by calculated sizes`() {
        val sorted = sorter.sortItems(items, SortSettings(mode = SortSettings.Mode.NAME))

        sorted.names() shouldBe listOf("alpha", "big", "small", "zeta", "large.txt", "tiny.txt")
    }

    @Test
    fun `estimates sort by their displayed total including the missing contribution`() {
        val estimated = directory("estimated").copy(computedSize = DirectorySize(5000, false, 4900, true))
        sorter.sortItems(
            listOf(file("file", 1000), estimated, directory("unknown")),
            SortSettings(mode = SortSettings.Mode.SIZE, reversed = true),
        ).names() shouldBe listOf("estimated", "file", "unknown")
    }
}
