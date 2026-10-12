package eu.darken.butler.explorer.core.sorting

import android.content.Context
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.MimeInfo
import eu.darken.butler.common.files.local.LocalPathLookup
import eu.darken.butler.common.files.metadata.FileType
import eu.darken.butler.explorer.core.SortSettings
import eu.darken.butler.explorer.core.engine.ExplorerItem
import eu.darken.butler.explorer.core.engine.TrashItemReference
import eu.darken.butler.workspace.core.Workspace
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import org.junit.jupiter.api.Test
import testhelpers.BaseTest
import kotlin.time.Instant
import kotlin.uuid.Uuid

class ExplorerItemSorterTest : BaseTest() {

    private val sorter = ExplorerItemSorter(
        workspaceId = Workspace.Id(),
        context = mockk<Context>(relaxed = true),
    )

    private fun lookup(
        name: String,
        fileType: FileType,
        size: Long? = null,
        modifiedAt: Long? = null,
    ) = LocalPathLookup(
        lookedUp = LocalPath.build("/a/$name"),
        fileType = fileType,
        size = size,
        modifiedAt = modifiedAt?.let { Instant.fromEpochMilliseconds(it) },
    )

    private fun directory(name: String, modifiedAt: Long? = null) = ExplorerItem.RegularDirectory(
        lookup = lookup(name, FileType.DIRECTORY, modifiedAt = modifiedAt),
    )

    private fun file(name: String, modifiedAt: Long? = null) = ExplorerItem.RegularFile(
        lookup = lookup(name, FileType.FILE, size = 1L, modifiedAt = modifiedAt),
        mimeType = MimeInfo("text/plain"),
    )

    private fun trashNested(name: String, size: Long, isDirectory: Boolean): ExplorerItem.Trash.Nested {
        val inner = if (isDirectory) {
            ExplorerItem.RegularDirectory(lookup = lookup(name, FileType.DIRECTORY, size = size))
        } else {
            ExplorerItem.RegularFile(
                lookup = lookup(name, FileType.FILE, size = size),
                mimeType = MimeInfo("text/plain"),
            )
        }
        return ExplorerItem.Trash.Nested(
            inner = inner,
            parentRef = TrashItemReference(
                itemId = Uuid.random(),
                displayName = "deleted".toCaString(),
                originalPath = LocalPath.build("/a"),
                trashPath = LocalPath.build("/trash"),
                deletedAt = Instant.fromEpochMilliseconds(0),
            ),
            relativePath = name,
        )
    }

    private fun List<ExplorerItem>.names() = map {
        when (it) {
            is ExplorerItem.Peek -> "peek:${it.path.name}"
            is ExplorerItem.Path -> it.path.name
            is ExplorerItem.Trash.Nested -> it.lookup.name
            else -> error("Unexpected item: $it")
        }
    }

    @Test
    fun `descending name sort keeps peeks first and folders above files`() {
        val items = listOf(
            file("alpha.txt"),
            directory("Zeta"),
            file("zeta.txt"),
            ExplorerItem.Peek(LocalPath.build("/a/peeked")),
            directory("Alpha"),
        )

        sorter.sortItems(items, SortSettings(mode = SortSettings.Mode.NAME, reversed = true)).names() shouldBe listOf(
            "peek:peeked",
            "Zeta",
            "Alpha",
            "zeta.txt",
            "alpha.txt",
        )
    }

    @Test
    fun `descending modified sort orders folders newest first, then files newest first`() {
        val items = listOf(
            file("old.txt", modifiedAt = 1_000L),
            directory("oldDir", modifiedAt = 2_000L),
            file("new.txt", modifiedAt = 4_000L),
            directory("newDir", modifiedAt = 3_000L),
        )

        sorter.sortItems(
            items,
            SortSettings(mode = SortSettings.Mode.MODIFIED_AT, reversed = true),
        ).names() shouldBe listOf("newDir", "oldDir", "new.txt", "old.txt")
    }

    @Test
    fun `ascending name sort orders folders then files`() {
        val items = listOf(
            file("zeta.txt"),
            directory("Zeta"),
            file("alpha.txt"),
            directory("Alpha"),
        )

        sorter.sortItems(items, SortSettings(mode = SortSettings.Mode.NAME)).names() shouldBe listOf(
            "Alpha",
            "Zeta",
            "alpha.txt",
            "zeta.txt",
        )
    }

    private val trashNestedItems = listOf(
        trashNested("b.txt", size = 400L, isDirectory = false),
        trashNested("dirA", size = 300L, isDirectory = true),
        trashNested("a.txt", size = 100L, isDirectory = false),
        trashNested("dirB", size = 200L, isDirectory = true),
    )

    @Test
    fun `descending name sort in a browsed trash folder keeps folders above files`() {
        sorter.sortItems(
            trashNestedItems,
            SortSettings(mode = SortSettings.Mode.NAME, reversed = true),
        ).names() shouldBe listOf("dirB", "dirA", "b.txt", "a.txt")
    }

    @Test
    fun `descending size sort in a browsed trash folder keeps folders above files`() {
        sorter.sortItems(
            trashNestedItems,
            SortSettings(mode = SortSettings.Mode.SIZE, reversed = true),
        ).names() shouldBe listOf("dirA", "dirB", "b.txt", "a.txt")
    }
}
