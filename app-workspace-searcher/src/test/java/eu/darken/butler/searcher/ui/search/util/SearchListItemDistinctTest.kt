package eu.darken.butler.searcher.ui.search.util

import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.local.LocalPathLookup
import eu.darken.butler.common.files.metadata.FileType
import eu.darken.butler.searcher.core.SearchItem
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import testhelpers.BaseTest

class SearchListItemDistinctTest : BaseTest() {

    private fun item(path: String, fileType: FileType = FileType.FILE): SearchItem = SearchItem.fromLookup(
        lookup = LocalPathLookup(
            lookedUp = LocalPath.build(path),
            fileType = fileType,
            size = 1L,
            modifiedAt = null,
            target = null,
        ),
        matchedQuery = "",
    )

    @Test
    fun `duplicate paths from overlapping roots collapse to one`() {
        val duplicate = "/storage/emulated/0/Download/report.pdf"
        val input = listOf(
            item(duplicate),
            item("/storage/emulated/0/Documents/notes.txt"),
            item(duplicate),
        )

        val result = input.distinctByPath()

        result.map { it.path.path } shouldContainExactly listOf(
            "/storage/emulated/0/Download/report.pdf",
            "/storage/emulated/0/Documents/notes.txt",
        )
    }

    @Test
    fun `same name in different directories is kept`() {
        val input = listOf(
            item("/storage/emulated/0/Download/config.json"),
            item("/storage/emulated/0/Documents/config.json"),
        )

        val result = input.distinctByPath()

        result.size shouldBe 2
        result.map { it.path.path } shouldContainExactly listOf(
            "/storage/emulated/0/Download/config.json",
            "/storage/emulated/0/Documents/config.json",
        )
    }

    @Test
    fun `first occurrence is preserved`() {
        val path = "/storage/emulated/0/a.txt"
        val first = item(path, FileType.FILE)
        val second = item(path, FileType.DIRECTORY)

        val result = listOf(first, second).distinctByPath()

        result.size shouldBe 1
        result.first() shouldBe first
    }

    @Test
    fun `empty list stays empty`() {
        emptyList<SearchItem>().distinctByPath() shouldBe emptyList()
    }
}
