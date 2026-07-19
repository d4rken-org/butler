package eu.darken.butler.searcher.core.sorting

import android.content.Context
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.local.LocalPathLookup
import eu.darken.butler.common.files.metadata.FileType
import eu.darken.butler.searcher.core.SearchItem
import eu.darken.butler.searcher.core.SearchSortSettings
import eu.darken.butler.workspace.core.Workspace
import io.kotest.matchers.collections.shouldContainExactly
import io.mockk.mockk
import org.junit.jupiter.api.Test
import testhelpers.BaseTest
import kotlin.time.Instant

class SearchItemSorterTest : BaseTest() {

    private val sorter = SearchItemSorter(
        workspaceId = Workspace.Id(),
        context = mockk<Context>(),
    )

    private fun item(
        name: String,
        createdAt: Instant? = null,
        fileType: FileType = FileType.FILE,
    ): SearchItem = SearchItem.fromLookup(
        lookup = LocalPathLookup(
            lookedUp = LocalPath.build("/sdcard/$name"),
            fileType = fileType,
            size = 1L,
            modifiedAt = null,
            target = null,
            createdAt = createdAt,
        ),
        matchedQuery = "",
    )

    private fun sortByCreatedAt(items: List<SearchItem>, reversed: Boolean = false) = sorter.sortItems(
        items = items,
        sortSettings = SearchSortSettings(mode = SearchSortSettings.Mode.CREATED_AT, reversed = reversed),
    )

    @Test
    fun `created at sorts items by creation time ascending`() {
        val oldest = item("oldest.txt", createdAt = Instant.fromEpochMilliseconds(1_000L))
        val middle = item("middle.txt", createdAt = Instant.fromEpochMilliseconds(2_000L))
        val newest = item("newest.txt", createdAt = Instant.fromEpochMilliseconds(3_000L))

        val sorted = sortByCreatedAt(listOf(newest, oldest, middle))

        sorted shouldContainExactly listOf(oldest, middle, newest)
    }

    @Test
    fun `items without creation time group first`() {
        val unknownA = item("unknown_a.txt")
        val unknownB = item("unknown_b.txt")
        val dated = item("dated.txt", createdAt = Instant.fromEpochMilliseconds(1_000L))

        val sorted = sortByCreatedAt(listOf(dated, unknownA, unknownB))

        sorted shouldContainExactly listOf(unknownA, unknownB, dated)
    }

    @Test
    fun `mixed null and dated items keep nulls first then sort by time`() {
        val unknown = item("unknown.txt")
        val older = item("older.txt", createdAt = Instant.fromEpochMilliseconds(1_000L))
        val newer = item("newer.txt", createdAt = Instant.fromEpochMilliseconds(2_000L))

        val sorted = sortByCreatedAt(listOf(newer, unknown, older))

        sorted shouldContainExactly listOf(unknown, older, newer)
    }

    @Test
    fun `directories are grouped before files`() {
        val file = item("file.txt", createdAt = Instant.fromEpochMilliseconds(1_000L))
        val directory = item("dir", createdAt = Instant.fromEpochMilliseconds(2_000L), fileType = FileType.DIRECTORY)

        val sorted = sortByCreatedAt(listOf(file, directory))

        sorted shouldContainExactly listOf(directory, file)
    }
}
